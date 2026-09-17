-- PAYMESH Distributed Payment Gateway
-- MySQL 8+. Run once as root. Application-level primary/backup is represented by
-- two databases on the same MySQL instance for a reproducible college demo.
CREATE DATABASE IF NOT EXISTS dc_paygateway CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS dc_paygateway_primary CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS dc_paygateway_backup CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE dc_paygateway;
CREATE TABLE IF NOT EXISTS merchants(merchant_id VARCHAR(64) PRIMARY KEY,name VARCHAR(120) NOT NULL,email VARCHAR(160),status VARCHAR(30) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS merchant_api_keys(api_key_id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_id VARCHAR(64) NOT NULL,api_key_hash VARCHAR(255) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS merchant_bank_accounts(account_ref VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),bank_name VARCHAR(100),account_last4 VARCHAR(4),status VARCHAR(30));
CREATE TABLE IF NOT EXISTS webhook_endpoints(webhook_id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_id VARCHAR(64),url VARCHAR(500),secret VARCHAR(255),active BOOLEAN DEFAULT TRUE);
CREATE TABLE IF NOT EXISTS customers(customer_id VARCHAR(64) PRIMARY KEY,name VARCHAR(120) NOT NULL,email VARCHAR(160),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS payment_tokens(token_id VARCHAR(64) PRIMARY KEY,customer_id VARCHAR(64),token_type VARCHAR(30),masked_value VARCHAR(80),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS orders(order_id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),customer_id VARCHAR(64),amount DECIMAL(15,2),currency CHAR(3),status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS payments(payment_id VARCHAR(64) PRIMARY KEY,order_id VARCHAR(64),customer_id VARCHAR(64),token_id VARCHAR(64),amount DECIMAL(15,2) NOT NULL,currency CHAR(3) DEFAULT 'INR',method VARCHAR(40),status VARCHAR(40),idempotency_key VARCHAR(160) UNIQUE,shard_id VARCHAR(30),lamport_ts BIGINT,settlement_cycle_id VARCHAR(64),primary_node_id VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS payment_attempts(attempt_id BIGINT AUTO_INCREMENT PRIMARY KEY,payment_id VARCHAR(64),processor_id VARCHAR(64),attempt_no INT,status VARCHAR(30),latency_ms BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS authorizations(authorization_id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),amount DECIMAL(15,2),status VARCHAR(30),authorized_at TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS captures(capture_id VARCHAR(64) PRIMARY KEY,authorization_id VARCHAR(64),amount DECIMAL(15,2),status VARCHAR(30),captured_at TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS refunds(refund_id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),amount DECIMAL(15,2),status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS disputes(dispute_id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),reason VARCHAR(255),status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS processors(processor_id VARCHAR(64) PRIMARY KEY,name VARCHAR(120),type VARCHAR(30),status VARCHAR(30),success_rate DECIMAL(5,2));
CREATE TABLE IF NOT EXISTS routing_rules(rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,method VARCHAR(40),processor_id VARCHAR(64),priority INT,active BOOLEAN DEFAULT TRUE);
CREATE TABLE IF NOT EXISTS processor_health_log(log_id BIGINT AUTO_INCREMENT PRIMARY KEY,processor_id VARCHAR(64),status VARCHAR(30),latency_ms BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS fee_configs(fee_id BIGINT AUTO_INCREMENT PRIMARY KEY,method VARCHAR(40),percent_fee DECIMAL(6,3),flat_fee DECIMAL(10,2),active BOOLEAN DEFAULT TRUE);
CREATE TABLE IF NOT EXISTS accounts(account_id VARCHAR(64) PRIMARY KEY,account_name VARCHAR(120),account_type VARCHAR(40),currency CHAR(3),balance DECIMAL(18,2) DEFAULT 0);
CREATE TABLE IF NOT EXISTS ledger_entries(entry_id VARCHAR(64) PRIMARY KEY,transaction_id VARCHAR(64),payment_id VARCHAR(64),account_id VARCHAR(64),entry_type VARCHAR(10),amount DECIMAL(18,2),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS settlement_cycles(cycle_id VARCHAR(64) PRIMARY KEY,cycle_date DATE,status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS netting_cycles(netting_id VARCHAR(64) PRIMARY KEY,cycle_id VARCHAR(64),status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS netting_results(result_id VARCHAR(64) PRIMARY KEY,netting_id VARCHAR(64),merchant_id VARCHAR(64),gross_amount DECIMAL(18,2),fees DECIMAL(18,2),net_amount DECIMAL(18,2));
CREATE TABLE IF NOT EXISTS payouts(payout_id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),result_id VARCHAR(64),amount DECIMAL(18,2),status VARCHAR(30),paid_at TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS idempotency_keys(key_value VARCHAR(160) PRIMARY KEY,payment_id VARCHAR(64),request_hash VARCHAR(128),response_json JSON,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS twophase_log(log_id BIGINT AUTO_INCREMENT PRIMARY KEY,transaction_id VARCHAR(64),participant VARCHAR(80),phase VARCHAR(30),status VARCHAR(30),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS webhook_deliveries(delivery_id BIGINT AUTO_INCREMENT PRIMARY KEY,webhook_id BIGINT,payment_id VARCHAR(64),status VARCHAR(30),attempt_no INT,delivered_at TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS nodes(node_id VARCHAR(30) PRIMARY KEY,node_name VARCHAR(100),priority INT,role VARCHAR(30),status VARCHAR(30),host VARCHAR(100),port INT,last_heartbeat TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS shards(shard_id VARCHAR(30) PRIMARY KEY,shard_key VARCHAR(100),primary_node_id VARCHAR(30));
CREATE TABLE IF NOT EXISTS shard_replicas(replica_id BIGINT AUTO_INCREMENT PRIMARY KEY,shard_id VARCHAR(30),node_id VARCHAR(30),role VARCHAR(30),status VARCHAR(30));
CREATE TABLE IF NOT EXISTS elections(election_id BIGINT AUTO_INCREMENT PRIMARY KEY,initiator_node_id VARCHAR(30),winner_node_id VARCHAR(30),algorithm VARCHAR(40),started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMP NULL,status VARCHAR(30));
CREATE TABLE IF NOT EXISTS clock_sync_log(log_id BIGINT AUTO_INCREMENT PRIMARY KEY,coordinator VARCHAR(80),node_name VARCHAR(80),offset_seconds DECIMAL(10,3),target_offset DECIMAL(10,3),adjustment_seconds DECIMAL(10,3),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS reconciliation_runs(run_id BIGINT AUTO_INCREMENT PRIMARY KEY,scope_name VARCHAR(80),status VARCHAR(30),started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMP NULL);
CREATE TABLE IF NOT EXISTS reconciliation_mismatches(mismatch_id BIGINT AUTO_INCREMENT PRIMARY KEY,run_id BIGINT,payment_id VARCHAR(64),field_name VARCHAR(80),primary_value TEXT,backup_value TEXT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS p2p_groups(group_id VARCHAR(64) PRIMARY KEY,name VARCHAR(120),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS group_members(group_id VARCHAR(64),customer_id VARCHAR(64),role VARCHAR(30),PRIMARY KEY(group_id,customer_id));
CREATE TABLE IF NOT EXISTS expenses(expense_id VARCHAR(64) PRIMARY KEY,group_id VARCHAR(64),paid_by VARCHAR(64),amount DECIMAL(15,2),description VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS expense_splits(split_id BIGINT AUTO_INCREMENT PRIMARY KEY,expense_id VARCHAR(64),customer_id VARCHAR(64),share_amount DECIMAL(15,2));
CREATE TABLE IF NOT EXISTS obligations(obligation_id VARCHAR(64) PRIMARY KEY,group_id VARCHAR(64),from_customer VARCHAR(64),to_customer VARCHAR(64),amount DECIMAL(15,2),status VARCHAR(30));
CREATE TABLE IF NOT EXISTS system_events(event_id BIGINT AUTO_INCREMENT PRIMARY KEY,event_type VARCHAR(80),node_id VARCHAR(30),message TEXT,lamport_ts BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS replication_log(replication_id BIGINT AUTO_INCREMENT PRIMARY KEY,payment_id VARCHAR(64),source_node VARCHAR(30),target_node VARCHAR(30),mode VARCHAR(20),sequence_no BIGINT,status VARCHAR(30),ack_at TIMESTAMP NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);

INSERT INTO merchants VALUES('M-1001','Demo Merchant','merchant@paymesh.local','ACTIVE',NOW()) ON DUPLICATE KEY UPDATE name='Demo Merchant';
INSERT INTO customers VALUES('C-1001','Demo Customer','customer@paymesh.local',NOW()) ON DUPLICATE KEY UPDATE name='Demo Customer';
INSERT INTO processors VALUES('UPI-Processor','UPI Processor','UPI','UP',99.00),('Card-Processor','Card Processor','CARD','UP',99.00),('Banking-Processor','Banking Processor','BANK','UP',99.00) ON DUPLICATE KEY UPDATE status='UP';
INSERT INTO accounts VALUES('CUSTOMER-CLEARING','Customer Clearing','ASSET','INR',0),('MERCHANT-PENDING','Merchant Pending','LIABILITY','INR',0) ON DUPLICATE KEY UPDATE account_name=VALUES(account_name);
INSERT INTO nodes VALUES('NODE-1','Payment Node 1',40,'PRIMARY','UP','localhost',8080,NULL),('NODE-2','Payment Node 2',30,'BACKUP','UP','localhost',8081,NULL) ON DUPLICATE KEY UPDATE node_name=VALUES(node_name);
INSERT INTO shards VALUES('SHARD-1','orders-even','NODE-1'),('SHARD-2','orders-odd','NODE-1') ON DUPLICATE KEY UPDATE primary_node_id='NODE-1';
INSERT IGNORE INTO shard_replicas(shard_id,node_id,role,status) VALUES('SHARD-1','NODE-1','PRIMARY','UP'),('SHARD-1','NODE-2','BACKUP','UP'),('SHARD-2','NODE-1','PRIMARY','UP'),('SHARD-2','NODE-2','BACKUP','UP');

USE dc_paygateway_backup;
CREATE TABLE IF NOT EXISTS replica_payments(payment_id VARCHAR(64) PRIMARY KEY,order_id VARCHAR(64),customer_id VARCHAR(64),amount DECIMAL(15,2),currency CHAR(3),method VARCHAR(40),status VARCHAR(40),idempotency_key VARCHAR(160) UNIQUE,shard_id VARCHAR(30),lamport_ts BIGINT,primary_node_id VARCHAR(30),replicated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS replica_ledger_entries(entry_id VARCHAR(64) PRIMARY KEY,transaction_id VARCHAR(64),payment_id VARCHAR(64),account_id VARCHAR(64),entry_type VARCHAR(10),amount DECIMAL(18,2),replicated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS replica_system_events(event_id BIGINT PRIMARY KEY,event_type VARCHAR(80),node_id VARCHAR(30),message TEXT,lamport_ts BIGINT,replicated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
