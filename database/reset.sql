USE dc_paygateway;
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE system_events; TRUNCATE TABLE replication_log; TRUNCATE TABLE payments; TRUNCATE TABLE payment_attempts; TRUNCATE TABLE ledger_entries; TRUNCATE TABLE elections; TRUNCATE TABLE clock_sync_log; TRUNCATE TABLE reconciliation_mismatches; TRUNCATE TABLE reconciliation_runs;
SET FOREIGN_KEY_CHECKS=1;
USE dc_paygateway_backup;
TRUNCATE TABLE replica_payments; TRUNCATE TABLE replica_ledger_entries; TRUNCATE TABLE replica_system_events;
