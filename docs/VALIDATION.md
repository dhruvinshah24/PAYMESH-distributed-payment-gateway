# PAYMESH Validation Notes

The current PAYMESH build was verified during development with the running local Docker environment before preparing the no-Docker team package:

- `GET /api/payments` returned HTTP 200 and an empty JSON array on a clean database.
- `GET /api/state` returned HTTP 200 with NODE-1 active, both nodes alive and synchronous replication enabled.
- `GET /api/consistency` returned HTTP 200 with primaryCount=0, backupCount=0, mismatches=0 and consistent=true.
- A previous cross-schema collation mismatch was corrected by making the database/schema collation consistent; the packaged `schema.sql` now creates all PAYMESH schemas with `utf8mb4_0900_ai_ci`.

For a teammate's machine, run `scripts/verify-paymesh.ps1` after starting MySQL, backend and frontend.

Note: no software package can honestly guarantee zero errors on every machine; the scripts fail fast with clear messages when Java, Maven, npm or MySQL is missing or misconfigured.
