# PAYMESH Architecture & Viva Notes

## 1. Application layers

**Presentation:** React + Vite dashboard. The UI polls the REST API and renders the actual node/replication/ledger state.

**Application:** Spring Boot service. Payment creation is synchronized and idempotency is handled before the payment state machine executes.

**Persistence:** MySQL. `dc_paygateway` represents the active store; `dc_paygateway_backup` contains the application-managed replica tables. The design deliberately calls this application-level primary-backup replication, not native MySQL replication.

## 2. Payment lifecycle

`REQUEST → VALIDATE → ROUTE → PRIMARY WRITE → BACKUP WRITE → ACK → COMMIT`

When NODE-1 fails, NODE-2 becomes the logical primary and writes authoritative payment state to `dc_paygateway_backup.replica_payments`. After NODE-1 recovery, resynchronization copies the authoritative state back to `dc_paygateway.payments`.

## 3. Experiment mapping

| Experiment | PAYMESH integration |
|---|---|
| RMI / RPC | `demos/rmi`, `demos/rpc` terminal programs |
| Multithreading | Spring service + standalone concurrency load test |
| Berkeley | Clock Synchronization page and `clock_sync_log` |
| Lamport | Payment/system event logical timestamps |
| Bully | Nodes & Election page + election records |
| Experiment 5 | Primary-backup failover, promotion, recovery, resync, consistency |
| 2PC | `demos/twophase` educational coordinator/participants |
| Ledger | Double-entry debit/credit verification |
| Settlement | Gross → fee → net settlement summary |

## 4. Bully model

The project uses the five-terminal configuration from Experiment 4: T1=10, T2=25, T3=40, T4=15, T5=30. The election is priority-based. T3 is the highest active terminal under normal operation; if T3 fails, T5 has the highest remaining priority.

## 5. Berkeley vs Lamport

Berkeley adjusts physical-clock offsets toward a coordinator-computed target. Lamport does not modify wall-clock time; it advances a logical counter and uses `max(local, received)+1` on message reception. They are therefore complementary rather than interchangeable.

## 6. Failure-injection acceptance test

A convincing Experiment 5 demonstration should show all of these transitions:

1. NODE-1 is primary.
2. A payment is synchronously replicated.
3. NODE-1 is failed.
4. Heartbeat/election state identifies the failure.
5. NODE-2 is elected/promoted.
6. A new payment is accepted by NODE-2.
7. NODE-1 recovers.
8. Replica state is resynchronized.
9. Primary and backup counts match.
10. Field mismatch count is zero.

## 7. Important scope boundary

The default lab keeps both stores on one MySQL instance so students can reproduce the experiment on a laptop. The logical node roles and replication protocol are application-level. Moving NODE-2 to a second JVM/database host is a deployment extension, not a prerequisite for understanding the algorithms.
