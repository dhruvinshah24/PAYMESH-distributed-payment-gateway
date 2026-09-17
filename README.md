# PAYMESH — Distributed Payment Gateway

A college-project-grade distributed payment gateway lab built around **Experiment 5: fault tolerance using primary-backup replication**, while integrating the earlier RMI/multithreading, Berkeley + Lamport clock synchronization, and Bully election experiments.

## Architecture

```text
React/Vite dashboard
        │ REST
        ▼
Spring Boot API / control plane
        │
        ├── Payment processing + smart routing
        ├── Idempotency + concurrency-safe service
        ├── Lamport logical clock
        ├── Primary/backup replication
        ├── Failure injection + recovery
        ├── Bully election model
        ├── Berkeley synchronization demo
        ├── Double-entry ledger verification
        └── Settlement/netting summary
        │
        ▼
MySQL: dc_paygateway
       ├── dc_paygateway_primary (reserved for multi-process extension)
       └── dc_paygateway_backup (replica_payments / replica_ledger_entries)
```

> **Academic terminology:** the primary/backup database layer is an **application-level synchronous primary-backup replication model**. It is not claimed to be native MySQL replication.

## Prerequisites — no Docker required

- Java 17+ (Java 21 also works)
- Maven 3.9+
- MySQL Server 8.x
- Node.js 20+
- npm
- Optional: MySQL Workbench

The recommended team setup uses **local MySQL**, Spring Boot, and Vite directly on the host. Docker is not required. This avoids Docker Desktop, container port conflicts, volume resets, and image downloads for teammates.

## 1. Database

Install MySQL Server 8.x and run the included PowerShell setup script:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\setup-mysql.ps1
```

Or open MySQL as root and run:

```sql
SOURCE /absolute/path/to/PAYMESH/database/schema.sql;
```

The schema creates `dc_paygateway`, `dc_paygateway_primary`, and `dc_paygateway_backup` and seeds demo nodes, processors, accounts, merchant and customer.

Update `backend/src/main/resources/application.properties` if your MySQL username/password differs from `root/root`.

## 2. Start backend

```bash
cd backend
mvn spring-boot:run
```

Backend: `http://localhost:8080`

Health: `GET http://localhost:8080/api/health`

## 3. Start frontend

```bash
cd frontend
npm install
npm run dev
```

Open the Vite URL, normally `http://localhost:5173`.

## Experiment 5 — viva sequence

Use **Experiment 5 → Run complete scenario** or execute manually:

1. Reset lab.
2. Create PAY-1001: ₹45,000 UPI.
3. Create PAY-1002: ₹25,000 Card.
4. Create PAY-1003: ₹75,000 Net Banking.
5. Fail NODE-1.
6. Start Bully election.
7. Promote NODE-2.
8. Create PAY-1004: ₹50,000 PhonePe/UPI on NODE-2.
9. Recover NODE-1.
10. Restore replication / resync.
11. Show `DATA IS CONSISTENT` and zero mismatches.

The dashboard exposes the state transition rather than merely changing labels.

## Other experiments

### Multithreading / idempotency
The service is designed to be concurrency-safe and uses an idempotency key to return the original result on a duplicate request. The earlier experiment explains why payment gateways are I/O-bound and why thread-safe idempotency state matters.

### Berkeley + Lamport
Clock Synchronization runs a Berkeley-style offset calculation for Payment, Routing, Processor, Ledger and Settlement. The payment lifecycle uses a Lamport counter for logical ordering.

### Bully
The Nodes & Election page shows the five-terminal academic model: T1=10, T2=25, T3=40, T4=15, T5=30. In the documented failure scenario, T3 fails and the highest remaining priority is T5.

### RMI / RPC
The `demos/rmi` and `demos/rpc` folders contain independent terminal demos so the educational communication experiments stay clearly separated from the REST web application.

## RMI demo

```bash
cd demos/rmi
javac *.java
# terminal 1
java RMIServer
# terminal 2
java RMIClient
```

## RPC demo

```bash
cd demos/rpc
javac *.java
# terminal 1
java RPCServer
# terminal 2
java RPCClient
```

## Replication modes

PAYMESH supports explicit **SYNCHRONOUS** and **ASYNCHRONOUS** application-level primary-backup modes. Synchronous mode treats backup acknowledgement as part of the replication/commit path; asynchronous mode makes the trade-off visible by allowing the primary path to proceed without waiting for an acknowledgement. Use the Experiment 5 controls to switch modes.

## Bully election model

The gateway data plane uses NODE-1/NODE-2 for primary-backup. Separately, the educational Bully control-plane model uses five terminals T1=10, T2=25, T3=40, T4=15, T5=30. This avoids incorrectly conflating the two different concepts while still demonstrating the algorithm from Experiment 4.

## Useful REST calls

```bash
curl http://localhost:8080/api/state
curl http://localhost:8080/api/payments
curl http://localhost:8080/api/consistency
curl http://localhost:8080/api/ledger/verify
curl http://localhost:8080/api/events
curl http://localhost:8080/api/settlement
curl http://localhost:8080/api/election
curl -X POST http://localhost:8080/api/election/start
curl -X POST http://localhost:8080/api/control/FAIL_PRIMARY
curl -X POST http://localhost:8080/api/control/START_ELECTION
curl -X POST http://localhost:8080/api/control/PROMOTE_BACKUP
curl -X POST http://localhost:8080/api/control/RESTORE_REPLICATION
```

## Important demo semantics

- **Synchronous mode:** primary writes and then backup acknowledgement is attempted before the payment is treated as replicated.
- **Dropped replication:** new primary writes can be marked `PENDING_REPLICATION`, making the consistency trade-off visible.
- **Failure injection:** node and processor controls are explicit and reversible.
- **Recovery:** resync copies primary payment state into the backup replica table and the consistency endpoint compares record counts and key fields.
- **Ledger:** every payment creates a debit and credit entry; `/api/ledger/verify` checks balance per transaction.

## Limitations / extension boundary

For a reproducible college lab, the default application runs as one Spring Boot process and uses two MySQL schemas to model primary/backup state. A future multi-process deployment can move NODE-2 to a second Spring Boot process/port while retaining the same REST contracts and replication protocol.

## 🚀 Final Viva Runbook

### Recommended team startup — local processes

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start-paymesh.ps1
```

This starts the local MySQL setup, backend, and frontend without Docker. See `docs/LOCAL-SETUP.md` for manual startup and troubleshooting.

A legacy Docker Compose file is kept only as an optional reference at `docs/docker-compose.optional.yml`; teammates do not need it.

### Experiment 5 — 60-second viva demonstration

Use **Experiment 5 → Run complete scenario**. The deterministic sequence is:

1. NODE-1 is primary.
2. PAY-1001 — ₹45,000 — GPay/UPI.
3. PAY-1002 — ₹25,000 — Credit Card.
4. PAY-1003 — ₹75,000 — HDFC Bank/Net Banking.
5. Inject NODE-1 failure.
6. Bully election runs; NODE-2 is promoted.
7. PAY-1004 — ₹50,000 — PhonePe/UPI is processed by the promoted node.
8. Recover NODE-1.
9. Resynchronize the recovered node.
10. Run consistency and ledger verification.

**Viva wording:** the replication shown by PAYMESH is *application-level primary-backup replication*. In normal synchronous mode, the backup acknowledgement is part of the commit path. During failover, the promoted node can operate in a degraded single-node state; recovery then resynchronizes the former primary. This distinction is intentional and avoids incorrectly claiming native MySQL replication.

### Distributed-computing mapping

- **Experiment 2:** concurrent request handling + idempotency.
- **Experiment 3:** Berkeley physical clock synchronization + Lamport logical ordering.
- **Experiment 4:** Bully coordinator election + heartbeat failure detection.
- **Experiment 5:** primary-backup replication + failover + recovery + consistency verification.

### Useful API endpoints

- `GET /api/state`
- `GET /api/payments`
- `GET /api/events`
- `GET /api/consistency`
- `GET /api/ledger/verify`
- `GET /api/clock-sync`
- `GET /api/settlement`
- `POST /api/payments`
- `POST /api/control/FAIL_PRIMARY`
- `POST /api/control/START_ELECTION`
- `POST /api/control/PROMOTE_BACKUP`
- `POST /api/control/RECOVER_PRIMARY`
- `POST /api/control/RESTORE_REPLICATION`
- `POST /api/experiment/5/run`
