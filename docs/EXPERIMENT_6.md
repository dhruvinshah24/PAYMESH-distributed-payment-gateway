# Experiment 6 — Implementation of Data Consistency and Replication Models

## Aim

Implement and demonstrate different data replication/consistency models in a distributed payment gateway: application-level synchronous replication, application-level asynchronous replication, replica lag, consistency verification, replica failure/recovery, and resynchronization.

## Objectives

1. Implement synchronous primary-backup replication where the primary commits only after the replica acknowledges the write.
2. Implement asynchronous replication where the primary commits immediately and the replica catches up in the background.
3. Make replica lag observable: show the primary and replica temporarily diverging during asynchronous replication, then converging.
4. Implement a deterministic consistency checker that compares primary and replica records field-by-field (payment ID, amount, method, status, version) and classifies mismatches.
5. Support a controlled replica failure/recovery cycle and demonstrate the synchronous-replication trade-off (no replica ack ⇒ commit blocked).
6. Support explicit resynchronization that restores consistency after failure or lag.

## Experiment 5 vs Experiment 6

Experiment 5 is about **fault tolerance**: primary failure, failure detection, Bully election, backup promotion, recovery and resync of the *coordinator* role.

Experiment 6 is about **data consistency**: given a fixed primary/replica topology (no election), how does the *choice of replication model* (synchronous vs asynchronous) affect whether the primary and replica agree at any given instant, and how is that verified and restored.

```
Experiment 5                          Experiment 6
PRIMARY FAILURE                       PAYMENT WRITE
   |                                     |
failure detection                +-------+-------+
   |                             |               |
Bully election               SYNCHRONOUS     ASYNCHRONOUS
   |                          Primary→Replica  Primary→Commit
backup becomes primary        Wait for ACK    Replica catches up later
   |                          Consistent now  Temporary replica lag
payments continue                |               |
   |                             +-------+-------+
old primary recovers                     |
   |                             CONSISTENCY CHECK
resynchronization                        |
                                   mismatch detection
                                          |
                                    replica resync
                                          |
                                  consistency restored
```

Experiment 6 deliberately keeps its own independent primary/replica-alive state (`Experiment6Service`), separate from Experiment 5's `PaymentService`/`NodeCluster`/`BullyElectionService`, and its own `PAY-6xxx` payment id range and `experiment_tag='EXPERIMENT_6'` marker — the two experiments never share mutable runtime state or demo data, so running one never disturbs the other.

## Architecture

- **Backend**: `Experiment6Service` (raw `JdbcTemplate`, no JPA — matches the rest of the codebase) + `Experiment6Controller` (`/api/experiment6/**`). All writes go to the existing `payments` (primary, `dc_paygateway`) and `replica_payments` (replica, `dc_paygateway_backup`) tables, scoped by `experiment_tag='EXPERIMENT_6'`. Replication is logged to the existing (previously unused) `replication_log` table; consistency checks are audited into `reconciliation_runs`/`reconciliation_mismatches`. Events are appended to the shared `system_events` table with `E6_*` event types and read back filtered by that prefix — no new event/streaming infrastructure.
- **Concurrency**: a dedicated daemon `ScheduledExecutorService` performs the delayed replica write for asynchronous replication. All state mutations are `synchronized` on the service instance (matching `PaymentService`'s existing coarse-locking convention), so a concurrent replica-fail/consistency-check/resync can never observe or produce torn state.
- **Frontend**: a new "Experiment 6" page inside the existing single-file `frontend/src/main.tsx` (the app has no router — pages are a `page` string + conditional render), polling `/api/experiment6/state|consistency|payments|events` every 2.5s only while that page is open.

## Synchronous replication

"Application-level synchronous primary-backup replication." Sequence: `PRIMARY WRITE → REPLICATION REQUEST → REPLICA WRITE → REPLICA ACK → COMMIT`. The primary write is always persisted; if the replica is alive, the primary blocks on the replica write and only then reports the payment as `SUCCESS`/committed. If the replica is down, the primary row is written with `status=REPLICATION_FAILED` and the response reports `committed:false` — the API never silently reports success. Latency is measured and returned (`latencyMs`) and reflected in the dashboard's "Replica lag" metric.

## Asynchronous replication

"Application-level asynchronous replication." Sequence: `PRIMARY WRITE → PRIMARY COMMIT → REPLICATION QUEUED → REPLICA WRITE → REPLICA SYNC COMPLETE`. The primary commits and responds immediately; the replica write is scheduled on a background thread after a configurable random delay (`paymesh.experiment6.async-delay-min-ms` / `-max-ms`, default 1500–2500ms). Until that task runs, the payment exists on the primary but not the replica — this is the observable "replica lag" / temporary inconsistency the consistency checker is meant to catch.

## Consistency verification

`GET /api/experiment6/consistency` compares `payments` and `replica_payments` (scoped to `EXPERIMENT_6`) by payment ID, then by `version`, `amount`, `status`, `method`, returning `MISSING_ON_REPLICA`, `EXTRA_ON_REPLICA`, `VERSION_MISMATCH`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, or `METHOD_MISMATCH` per differing record. The comparison is computed fresh from the database on every call (deterministic, no caching). Each explicit check is also persisted as an audit row in `reconciliation_runs`/`reconciliation_mismatches`.

## Replica failure, recovery, resynchronization

`POST /api/experiment6/replica/fail` and `/replica/recover` toggle an independent "replica alive" flag. While down, synchronous writes are blocked (not silently accepted) and asynchronous background replication attempts fail without retry. `POST /api/experiment6/resync` recomputes the mismatch set, copies missing/stale records from the (authoritative) primary into the replica, and re-runs the consistency check, emitting `E6_RESYNC_STARTED` → one `E6_RESYNC_RECORD` per row → `E6_RESYNC_COMPLETED` → `E6_CONSISTENCY_CHECK` → `E6_CONSISTENCY_RESTORED`.

## How to run

```powershell
# one-time: provision/upgrade the database
mysql -u root -p < database/schema.sql                    # fresh install (columns already included)
mysql -u root -p < database/migration_experiment6.sql      # OR, on an existing dc_paygateway DB

# backend
cd backend
$env:DB_PASSWORD='your-mysql-password'
mvn spring-boot:run

# frontend
cd frontend
npm install
npm run dev
```

Open the app (`http://localhost:5173`) and click **Experiment 6** in the sidebar.

## API endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/experiment6/state` | Current Experiment 6 state (model, node status, replication, consistency summary) |
| GET | `/api/experiment6/consistency` | Run and return a full consistency verification |
| POST | `/api/experiment6/reset` | Reset Experiment 6's data/state only |
| POST | `/api/experiment6/sync` | Execute a synchronous replication write |
| POST | `/api/experiment6/async` | Execute an asynchronous replication write |
| POST | `/api/experiment6/replica/fail` | Fail the Experiment 6 replica (NODE-2) |
| POST | `/api/experiment6/replica/recover` | Recover the Experiment 6 replica |
| POST | `/api/experiment6/resync` | Resynchronize replica from primary |
| POST | `/api/experiment6/model/{model}` | Switch active model (`SYNCHRONOUS`/`ASYNCHRONOUS`) |
| GET | `/api/experiment6/payments` | Primary and replica payment records |
| GET | `/api/experiment6/events` | Experiment 6 event log (`E6_*`) |

## Expected output

- Synchronous write: primary and replica record counts equal immediately, 0 mismatches.
- Asynchronous write, checked immediately: primary count = replica count + 1, 1 mismatch (`MISSING_ON_REPLICA`), reported as `TEMPORARILY INCONSISTENT`.
- Same check after the configured delay: counts equal again, 0 mismatches, `CONSISTENT`.
- Synchronous write while replica is down: `committed:false`, primary shows `REPLICATION_FAILED`, replica unchanged.
- After recover + resync: primary = replica, 0 mismatches, `CONSISTENT`.

## Viva questions

**What is synchronous replication?** The primary waits for the replica acknowledgement before committing the transaction. This provides immediate consistency between the participating replicas, at the cost of additional replication latency and dependence on replica availability.

**What is asynchronous replication?** The primary commits without waiting for the replica. The replica is updated in the background, so there can be temporary replica lag before the data converges.

**What is the main difference from Experiment 5?** Experiment 5 focuses on fault tolerance, failure detection, coordinator election, and primary promotion. Experiment 6 focuses on how replicated data is synchronized and how consistency differs between synchronous and asynchronous replication.

**Why does the mismatch appear?** Because asynchronous replication allows the primary to commit before the replica has applied the update.

**How is consistency restored?** The replica catches up through asynchronous replication or explicit resynchronization, after which the consistency checker verifies that the primary and replica contain matching records.
