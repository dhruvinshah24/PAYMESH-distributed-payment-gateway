-- PAYMESH Experiment 6 migration
-- Run ONCE against an already-provisioned dev database (schema.sql already has these
-- columns inline for brand-new installs). Additive, nullable/defaulted columns only —
-- safe for Experiment 5's existing explicit-column INSERT/SELECT statements.
USE dc_paygateway;
ALTER TABLE payments ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE payments ADD COLUMN experiment_tag VARCHAR(20);
ALTER TABLE payments ADD COLUMN replication_model VARCHAR(20);
ALTER TABLE payments ADD COLUMN replication_status VARCHAR(20);

USE dc_paygateway_backup;
ALTER TABLE replica_payments ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE replica_payments ADD COLUMN experiment_tag VARCHAR(20);
