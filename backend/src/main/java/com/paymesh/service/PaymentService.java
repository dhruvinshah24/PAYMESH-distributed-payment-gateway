package com.paymesh.service;

import com.paymesh.dto.PaymentRequest;
import com.paymesh.cluster.NodeCluster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PaymentService {
    private final JdbcTemplate db;
    private final NodeCluster cluster;
    private final BullyElectionService bully;
    private final AtomicLong lamport = new AtomicLong(0);
    private final Map<String, Map<String, Object>> idempotencyCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> processors = new ConcurrentHashMap<>();
    private volatile boolean primaryAlive = true;
    private volatile boolean backupAlive = true;
    private volatile boolean replicationEnabled = true;
    private volatile String replicationMode = "SYNCHRONOUS";
    private volatile String activePrimary = "NODE-1";
    private volatile String coordinator = "NODE-1";

    public PaymentService(JdbcTemplate db, NodeCluster cluster, BullyElectionService bully) {
        this.db = db;
        this.cluster = cluster;
        this.bully = bully;
        processors.put("UPI-Processor", true);
        processors.put("Card-Processor", true);
        processors.put("Banking-Processor", true);
    }

    @Transactional
    public synchronized Map<String, Object> create(PaymentRequest r) {
        validate(r);
        if (r.idempotencyKey() != null && !r.idempotencyKey().isBlank()) {
            Map<String, Object> cached = idempotencyCache.get(r.idempotencyKey());
            if (cached != null) return withFlag(cached, true);
            List<Map<String,Object>> old = db.queryForList("SELECT payment_id,status,amount,currency,method,lamport_ts,primary_node_id FROM payments WHERE idempotency_key=?", r.idempotencyKey());
            if (!old.isEmpty()) { Map<String,Object> replay = new LinkedHashMap<>(old.get(0)); replay.put("idempotentReplay", true); return replay; }
            List<Map<String,Object>> oldReplica = db.queryForList("SELECT payment_id,status,amount,currency,method,lamport_ts,primary_node_id FROM dc_paygateway_backup.replica_payments WHERE idempotency_key=?", r.idempotencyKey());
            if (!oldReplica.isEmpty()) { Map<String,Object> replay = new LinkedHashMap<>(oldReplica.get(0)); replay.put("idempotentReplay", true); return replay; }
        }
        if (!isNodeAvailable(activePrimary)) {
            if (backupAlive) elect(); else throw new IllegalStateException("No payment node is available");
        }
        String method = normalizeMethod(r.method());
        String processor = Optional.ofNullable(r.processor()).filter(p -> !p.isBlank()).orElse(route(method));
        if (!processors.getOrDefault(processor, false)) throw new IllegalStateException("Processor unavailable: " + processor);
        long ts = tick();
        String paymentId = nextPaymentId();
        String shard = shardFor(r.orderId());
        String status = (replicationEnabled && "SYNCHRONOUS".equals(replicationMode) && !backupAlive) ? "PENDING_REPLICATION" : "SUCCESS";
        boolean replicated = false;

        if ("NODE-1".equals(activePrimary)) {
            db.update("INSERT INTO payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                    paymentId,r.orderId(),r.customerId(),r.amount(),currency(r.currency()),method,status,r.idempotencyKey(),shard,ts,activePrimary);
            db.update("INSERT INTO payment_attempts(payment_id,processor_id,attempt_no,status,latency_ms) VALUES(?,?,?,?,?)",paymentId,processor,1,"SUCCESS",120);
            appendLedger(paymentId,r.amount());
            if (replicationEnabled && backupAlive) { replicatePayment(paymentId,r,method,status,shard,ts,activePrimary); replicateLedger(paymentId); replicated=true; }
        } else {
            // NODE-2 is now the logical primary. Its authoritative payment state lives in the backup schema.
            // NODE-1 can only be acknowledged once it has recovered.
            db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,replicated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW())",
                    paymentId,r.orderId(),r.customerId(),r.amount(),currency(r.currency()),method,status,r.idempotencyKey(),shard,ts,activePrimary);
            if (primaryAlive && replicationEnabled) {
                db.update("INSERT INTO payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                        paymentId,r.orderId(),r.customerId(),r.amount(),currency(r.currency()),method,status,r.idempotencyKey(),shard,ts,activePrimary);
                appendLedger(paymentId,r.amount());
                replicateLedger(paymentId);
                replicated=true;
            }
        }
        appendEvent("PAYMENT_CREATED", activePrimary, "Payment " + paymentId + " created on " + activePrimary, ts);
        if (replicated) appendEvent("REPLICATION_ACK", "NODE-2", "Replica acknowledged " + paymentId, tick());
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("paymentId",paymentId); out.put("status",status); out.put("processor",processor); out.put("node",activePrimary);
        out.put("lamportTs",ts); out.put("shardId",shard); out.put("replicated",replicated);
        out.put("consistencyMode", !replicationEnabled ? "REPLICATION_DISABLED" : replicationMode + "_PRIMARY_BACKUP");
        out.put("timestamp",Instant.now().toString()); out.put("idempotentReplay",false);
        if(r.idempotencyKey()!=null) {
            idempotencyCache.put(r.idempotencyKey(),out);
            try { db.update("INSERT INTO idempotency_keys(key_value,payment_id,request_hash,response_json) VALUES(?,?,?,CAST(? AS JSON)) ON DUPLICATE KEY UPDATE payment_id=VALUES(payment_id),response_json=VALUES(response_json)", r.idempotencyKey(), paymentId, Integer.toHexString(r.toString().hashCode()), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(out)); } catch (Exception ignored) {}
        }
        return out;
    }

    private void validate(PaymentRequest r) {
        if (r == null || r.amount() == null || r.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (r.orderId() == null || r.orderId().isBlank()) throw new IllegalArgumentException("Order ID is required");
        if (r.customerId() == null || r.customerId().isBlank()) throw new IllegalArgumentException("Customer ID is required");
    }

    private String normalizeMethod(String m) {
        String v = Optional.ofNullable(m).orElse("UPI").trim().toUpperCase(Locale.ROOT);
        if (v.equals("CREDIT CARD") || v.equals("DEBIT CARD")) return "CARD";
        if (v.equals("NET BANKING") || v.equals("NETBANKING") || v.equals("BANK")) return "NETBANKING";
        return v;
    }

    private String route(String method) {
        return switch (method) {
            case "CARD" -> "Card-Processor";
            case "NETBANKING" -> "Banking-Processor";
            default -> "UPI-Processor";
        };
    }

    private String currency(String c) { return Optional.ofNullable(c).filter(x -> !x.isBlank()).orElse("INR").toUpperCase(Locale.ROOT); }

    private String shardFor(String orderId) { return Math.abs(orderId.hashCode()) % 2 == 0 ? "SHARD-1" : "SHARD-2"; }

    private long tick() { return lamport.incrementAndGet(); }

    private String nextPaymentId() {
        Integer a = db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(payment_id,5) AS UNSIGNED)),1000) FROM payments", Integer.class);
        Integer b = db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(payment_id,5) AS UNSIGNED)),1000) FROM dc_paygateway_backup.replica_payments", Integer.class);
        int n = Math.max(Objects.requireNonNull(a), Objects.requireNonNull(b)) + 1;
        return "PAY-" + String.format("%04d", n);
    }

    private void appendLedger(String paymentId, BigDecimal amount) {
        String tx = "TX-" + paymentId;
        db.update("INSERT INTO ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tx, paymentId, "CUSTOMER-CLEARING", "DEBIT", amount);
        db.update("INSERT INTO ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tx, paymentId, "MERCHANT-PENDING", "CREDIT", amount);
    }

    private void replicatePayment(String id, PaymentRequest r, String method, String status, String shard, long ts, String node) {
        db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,replicated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),customer_id=VALUES(customer_id),amount=VALUES(amount),currency=VALUES(currency),method=VALUES(method),status=VALUES(status),idempotency_key=VALUES(idempotency_key),shard_id=VALUES(shard_id),lamport_ts=VALUES(lamport_ts),primary_node_id=VALUES(primary_node_id),replicated_at=NOW()",
                id, r.orderId(), r.customerId(), r.amount(), currency(r.currency()), method, status, r.idempotencyKey(), shard, ts, node);
    }

    private void replicateLedger(String paymentId) {
        db.update("INSERT INTO dc_paygateway_backup.replica_ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount,replicated_at) SELECT entry_id,transaction_id,payment_id,account_id,entry_type,amount,NOW() FROM ledger_entries WHERE payment_id=? ON DUPLICATE KEY UPDATE amount=VALUES(amount),replicated_at=NOW()", paymentId);
    }

    private boolean isNodeAvailable(String node) { return "NODE-1".equals(node) ? primaryAlive : backupAlive; }

    public Map<String,Object> state() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("activePrimary", activePrimary); m.put("coordinator", coordinator);
        m.put("primaryAlive", primaryAlive); m.put("backupAlive", backupAlive);
        m.put("replicationEnabled", replicationEnabled); m.put("lamport", lamport.get());
        m.put("processors", new LinkedHashMap<>(processors));
        m.put("replicationMode", replicationMode);
        m.put("cluster", cluster.snapshot());
        m.put("primaryNode", node("NODE-1")); m.put("backupNode", node("NODE-2"));
        return m;
    }

    private Map<String,Object> node(String id) {
        boolean alive = "NODE-1".equals(id) ? primaryAlive : backupAlive;
        Map<String,Object> n = new LinkedHashMap<>(); n.put("id",id); n.put("status",alive?"UP":"DOWN");
        n.put("role", id.equals(activePrimary)?"PRIMARY":"BACKUP"); n.put("priority", id.equals("NODE-1")?40:30); return n;
    }

    public synchronized Map<String,Object> control(String action) {
        switch (action.toUpperCase(Locale.ROOT)) {
            case "FAIL_PRIMARY" -> { primaryAlive = false; cluster.fail("NODE-1"); bully.fail("T3"); appendEvent("NODE_FAILURE", "NODE-1", "NODE-1 primary failure injected", tick()); }
            case "RECOVER_PRIMARY" -> { primaryAlive = true; cluster.recover("NODE-1"); bully.recover("T3"); appendEvent("NODE_RECOVERY", "NODE-1", "NODE-1 recovered", tick()); }
            case "FAIL_BACKUP" -> { backupAlive = false; cluster.fail("NODE-2"); appendEvent("NODE_FAILURE", "NODE-2", "NODE-2 backup failure injected", tick()); }
            case "RECOVER_BACKUP" -> { backupAlive = true; cluster.recover("NODE-2"); appendEvent("NODE_RECOVERY", "NODE-2", "NODE-2 recovered", tick()); }
            case "DROP_REPLICATION" -> { replicationEnabled = false; appendEvent("REPLICATION_DROP", activePrimary, "Replication link disabled", tick()); }
            case "SET_ASYNC" -> { replicationMode = "ASYNCHRONOUS"; appendEvent("REPLICATION_MODE", activePrimary, "Replication mode changed to ASYNCHRONOUS", tick()); }
            case "SET_SYNC" -> { replicationMode = "SYNCHRONOUS"; appendEvent("REPLICATION_MODE", activePrimary, "Replication mode changed to SYNCHRONOUS", tick()); }
            case "RESTORE_REPLICATION", "RESYNC" -> { replicationEnabled = true; resync(); appendEvent("RESYNC", "NODE-2", "Primary-backup resynchronization completed", tick()); }
            case "ELECT", "START_ELECTION" -> { cluster.elect(); bully.startElection(); elect(); }
            case "PROMOTE_BACKUP" -> promoteBackup();
            case "FAIL_UPI" -> processors.put("UPI-Processor", false);
            case "RECOVER_UPI" -> processors.put("UPI-Processor", true);
            case "FAIL_CARD" -> processors.put("Card-Processor", false);
            case "RECOVER_CARD" -> processors.put("Card-Processor", true);
            case "FAIL_BANK" -> processors.put("Banking-Processor", false);
            case "RECOVER_BANK" -> processors.put("Banking-Processor", true);
            case "RESET" -> { reset(); bully.reset(); }
            default -> throw new IllegalArgumentException("Unknown control action: " + action);
        }
        return state();
    }

    private void elect() {
        String old = coordinator;
        coordinator = backupAlive ? "NODE-2" : (primaryAlive ? "NODE-1" : "NONE");
        if ("NODE-2".equals(coordinator)) activePrimary = "NODE-2";
        else if ("NODE-1".equals(coordinator)) activePrimary = "NODE-1";
        db.update("INSERT INTO elections(initiator_node_id,winner_node_id,algorithm,started_at,completed_at,status) VALUES(?,?,?,?,NOW(),'COMPLETED')",
                old, coordinator, "BULLY", new java.sql.Timestamp(System.currentTimeMillis()));
        appendEvent("BULLY_ELECTION", coordinator, "Election completed. Coordinator: " + coordinator, tick());
    }

    private void promoteBackup() {
        if (!backupAlive) throw new IllegalStateException("Backup is down");
        activePrimary = "NODE-2"; coordinator = "NODE-2";
        primaryAlive = false;
        appendEvent("PRIMARY_PROMOTED", "NODE-2", "NODE-2 promoted to primary", tick());
    }

    private void resync() {
        if (!backupAlive) throw new IllegalStateException("Backup must be recovered before resync");
        if ("NODE-2".equals(activePrimary)) {
            db.update("INSERT INTO payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,created_at,updated_at) SELECT payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,NOW(),NOW() FROM dc_paygateway_backup.replica_payments ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),customer_id=VALUES(customer_id),amount=VALUES(amount),currency=VALUES(currency),method=VALUES(method),status=VALUES(status),idempotency_key=VALUES(idempotency_key),shard_id=VALUES(shard_id),lamport_ts=VALUES(lamport_ts),primary_node_id=VALUES(primary_node_id),updated_at=NOW()");
            db.update("INSERT INTO ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount,created_at) SELECT entry_id,transaction_id,payment_id,account_id,entry_type,amount,NOW() FROM dc_paygateway_backup.replica_ledger_entries ON DUPLICATE KEY UPDATE amount=VALUES(amount)");
        } else {
            db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,replicated_at) SELECT payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,NOW() FROM dc_paygateway.payments ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),customer_id=VALUES(customer_id),amount=VALUES(amount),currency=VALUES(currency),method=VALUES(method),status=VALUES(status),idempotency_key=VALUES(idempotency_key),shard_id=VALUES(shard_id),lamport_ts=VALUES(lamport_ts),primary_node_id=VALUES(primary_node_id),replicated_at=NOW()");
            db.update("INSERT INTO dc_paygateway_backup.replica_ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount,replicated_at) SELECT entry_id,transaction_id,payment_id,account_id,entry_type,amount,NOW() FROM ledger_entries ON DUPLICATE KEY UPDATE amount=VALUES(amount),replicated_at=NOW()");
        }
    }

    private void reset() {
        cluster.recover("NODE-1"); cluster.recover("NODE-2");
        primaryAlive=true; backupAlive=true; replicationEnabled=true; replicationMode="SYNCHRONOUS"; activePrimary="NODE-1"; coordinator="NODE-1";
        processors.replaceAll((k,v)->true); appendEvent("SYSTEM_RESET", "NODE-1", "Demo controls reset", tick());
    }

    private Map<String,Object> withFlag(Map<String,Object> base, boolean replay) { Map<String,Object> copy=new LinkedHashMap<>(base); copy.put("idempotentReplay", replay); return copy; }

    private void appendEvent(String type, String node, String message, long ts) {
        try { db.update("INSERT INTO system_events(event_type,node_id,message,lamport_ts) VALUES(?,?,?,?)", type,node,message,ts); } catch (Exception ignored) {}
    }

    public List<Map<String,Object>> payments() {
        return db.queryForList("SELECT p.payment_id,p.order_id,p.customer_id,p.amount,p.currency,p.method,p.status,p.idempotency_key,p.shard_id,p.lamport_ts,p.primary_node_id,p.created_at,p.updated_at,(SELECT processor_id FROM payment_attempts a WHERE a.payment_id=p.payment_id ORDER BY attempt_no DESC LIMIT 1) processor_id FROM payments p UNION ALL SELECT b.payment_id,b.order_id,b.customer_id,b.amount,b.currency,b.method,b.status,b.idempotency_key,b.shard_id,b.lamport_ts,b.primary_node_id,b.replicated_at,b.replicated_at,NULL FROM dc_paygateway_backup.replica_payments b WHERE NOT EXISTS (SELECT 1 FROM payments p2 WHERE p2.payment_id=b.payment_id) ORDER BY created_at DESC LIMIT 100");
    }
    public List<Map<String,Object>> events() { return db.queryForList("SELECT event_id,event_type,node_id,message,lamport_ts,created_at FROM system_events ORDER BY event_id DESC LIMIT 100"); }
    public Map<String,Object> payment(String id) {
        List<Map<String,Object>> p=db.queryForList("SELECT * FROM payments WHERE payment_id=?",id); if(p.isEmpty()) throw new NoSuchElementException("Payment not found");
        Map<String,Object> out=new LinkedHashMap<>(p.get(0)); out.put("attempts",db.queryForList("SELECT * FROM payment_attempts WHERE payment_id=? ORDER BY attempt_no",id));
        out.put("ledger",db.queryForList("SELECT * FROM ledger_entries WHERE payment_id=? ORDER BY created_at",id)); return out;
    }
    public Map<String,Object> consistency() {
        int p=count("SELECT COUNT(*) FROM payments"), b=count("SELECT COUNT(*) FROM dc_paygateway_backup.replica_payments");
        int mismatch=count("SELECT COUNT(*) FROM payments p LEFT JOIN dc_paygateway_backup.replica_payments b ON p.payment_id=b.payment_id WHERE b.payment_id IS NULL OR p.status<>b.status OR p.amount<>b.amount OR p.lamport_ts<>b.lamport_ts");
        return Map.of("primaryCount",p,"backupCount",b,"mismatches",mismatch,"consistent",mismatch==0 && p==b,"mode",replicationEnabled?"SYNCHRONOUS":"DISABLED");
    }
    private int count(String sql){Integer x=db.queryForObject(sql,Integer.class);return x==null?0:x;}
    public Map<String,Object> ledgerVerification(){int unbalanced=count("SELECT COUNT(*) FROM (SELECT transaction_id, SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) balance FROM ledger_entries GROUP BY transaction_id HAVING balance<>0) x"); int entries=count("SELECT COUNT(*) FROM ledger_entries"); return Map.of("unbalancedTransactions",unbalanced,"entries",entries,"balanced",unbalanced==0);}
    public Map<String,Object> clockSync(){
        List<Map<String,Object>> nodes=List.of(Map.of("node","Payment","offset",10),Map.of("node","Routing","offset",5),Map.of("node","Processor","offset",-2),Map.of("node","Ledger","offset",15),Map.of("node","Settlement","offset",-5));
        double avg=nodes.stream().mapToDouble(n->((Number)n.get("offset")).doubleValue()).average().orElse(0); double target=Math.rint(avg);
        for(Map<String,Object> n:nodes) db.update("INSERT INTO clock_sync_log(coordinator,node_name,offset_seconds,target_offset,adjustment_seconds) VALUES(?,?,?,?,?)","Payment",n.get("node"),n.get("offset"),target,target-((Number)n.get("offset")).doubleValue());
        return Map.of("algorithm","BERKELEY","targetOffset",target,"nodes",nodes,"lamport",lamport.get());
    }
    @Transactional
    public synchronized Map<String,Object> runExperiment5() {
        reset();
        // Remove only the deterministic viva records so repeated runs remain readable.
        db.update("DELETE FROM payment_attempts WHERE payment_id LIKE 'PAY-100%'");
        db.update("DELETE FROM ledger_entries WHERE payment_id LIKE 'PAY-100%'");
        db.update("DELETE FROM payments WHERE payment_id LIKE 'PAY-100%'");
        db.update("DELETE FROM dc_paygateway_backup.replica_payments WHERE payment_id LIKE 'PAY-100%'");
        appendEvent("EXPERIMENT_5_START", "NODE-1", "Starting deterministic primary-backup viva scenario", tick());
        demoPayment("PAY-1001", "ORD-1001", new BigDecimal("45000"), "UPI", "GPay");
        demoPayment("PAY-1002", "ORD-1002", new BigDecimal("25000"), "CARD", "Credit Card");
        demoPayment("PAY-1003", "ORD-1003", new BigDecimal("75000"), "NETBANKING", "HDFC Bank");
        control("FAIL_PRIMARY");
        control("START_ELECTION");
        control("PROMOTE_BACKUP");
        demoPayment("PAY-1004", "ORD-1004", new BigDecimal("50000"), "UPI", "PhonePe");
        control("RECOVER_PRIMARY");
        control("RESTORE_REPLICATION");
        appendEvent("EXPERIMENT_5_COMPLETE", "NODE-1", "Failover, recovery and resynchronization complete", tick());
        return Map.of("scenario", "EXPERIMENT_5", "payments", List.of("PAY-1001","PAY-1002","PAY-1003","PAY-1004"), "state", state(), "consistency", consistency(), "ledger", ledgerVerification());
    }

    private void demoPayment(String paymentId, String orderId, BigDecimal amount, String method, String processorChoice) {
        String processor = switch (method) { case "CARD" -> "Card-Processor"; case "NETBANKING" -> "Banking-Processor"; default -> "UPI-Processor"; };
        long ts=tick(); String shard=shardFor(orderId);
        if ("NODE-1".equals(activePrimary)) {
            db.update("INSERT INTO payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",paymentId,orderId,"C-1001",amount,"INR",method,"SUCCESS","VIVA-"+paymentId,shard,ts,activePrimary);
            db.update("INSERT INTO payment_attempts(payment_id,processor_id,attempt_no,status,latency_ms) VALUES(?,?,?,?,?)",paymentId,processor,1,"SUCCESS",180); appendLedger(paymentId,amount); replicatePayment(paymentId,new PaymentRequest(orderId,"C-1001",amount,"INR",method,processor,"VIVA-"+paymentId),method,"SUCCESS",shard,ts,activePrimary); replicateLedger(paymentId);
        } else {
            db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",paymentId,orderId,"C-1001",amount,"INR",method,"SUCCESS","VIVA-"+paymentId,shard,ts,activePrimary);
            db.update("INSERT INTO payment_attempts(payment_id,processor_id,attempt_no,status,latency_ms) VALUES(?,?,?,?,?)",paymentId,processor,1,"SUCCESS",180);
            db.update("INSERT INTO dc_paygateway_backup.replica_ledger_entries(entry_id,transaction_id,payment_id,account_id,entry_type,amount) VALUES(?,?,?,?,?,?),(?,?,?,?,?,?)",UUID.randomUUID().toString(),"TX-"+paymentId,paymentId,"CUSTOMER-CLEARING","DEBIT",amount,UUID.randomUUID().toString(),"TX-"+paymentId,paymentId,"MERCHANT-PENDING","CREDIT",amount);
        }
        appendEvent("PAYMENT_COMMITTED",activePrimary,processorChoice+" routed for "+paymentId+" ₹"+amount,tick());
    }

    public Map<String,Object> settlement(){int success=count("SELECT COUNT(*) FROM payments WHERE status='SUCCESS'"); BigDecimal gross=db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE status='SUCCESS'",BigDecimal.class); BigDecimal fees=gross.multiply(new BigDecimal("0.020")); return Map.of("successfulPayments",success,"gross",gross,"fees",fees,"net",gross.subtract(fees),"status","READY_FOR_SETTLEMENT");}
}
