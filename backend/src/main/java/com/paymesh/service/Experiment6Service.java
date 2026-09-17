package com.paymesh.service;

import com.paymesh.dto.Experiment6PaymentRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment 6 — Application-level synchronous/asynchronous primary-backup replication
 * and consistency verification. Deliberately independent of PaymentService/NodeCluster/
 * BullyElectionService (Experiment 5) so failing/recovering this experiment's replica can
 * never affect Experiment 5's node state. Shares only the JdbcTemplate bean and the
 * payments/replica_payments/system_events tables, scoped by experiment_tag='EXPERIMENT_6'
 * and a dedicated PAY-6xxx id range so the two experiments' demo data never collide.
 */
@Service
public class Experiment6Service {
    private static final String TAG = "EXPERIMENT_6";

    private final JdbcTemplate db;
    private final int asyncDelayMinMs;
    private final int asyncDelayMaxMs;
    private final Random random = new Random();
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicInteger pendingReplications = new AtomicInteger(0);
    private final AtomicLong lastLagMs = new AtomicLong(0);
    private final ScheduledExecutorService asyncExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "exp6-async-replicator");
        t.setDaemon(true);
        return t;
    });

    private volatile String activeModel = "SYNCHRONOUS";
    private volatile boolean replicaAlive = true;

    public Experiment6Service(JdbcTemplate db,
                               @Value("${paymesh.experiment6.async-delay-min-ms:1500}") int asyncDelayMinMs,
                               @Value("${paymesh.experiment6.async-delay-max-ms:2500}") int asyncDelayMaxMs) {
        this.db = db;
        this.asyncDelayMinMs = asyncDelayMinMs;
        this.asyncDelayMaxMs = asyncDelayMaxMs;
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- writes

    public synchronized Map<String, Object> syncWrite(Experiment6PaymentRequest r) {
        validate(r);
        String paymentId = resolvePaymentId(r.paymentId());
        String method = r.method().trim();
        long ts = seq.incrementAndGet();
        int version = nextVersion(paymentId);

        boolean replicaWasAlive = replicaAlive;
        String status = replicaWasAlive ? "SUCCESS" : "REPLICATION_FAILED";
        String replStatus = replicaWasAlive ? "PENDING" : "FAILED";
        upsertPrimary(paymentId, r.amount(), method, status, version, "SYNCHRONOUS", replStatus, ts);
        appendEvent("E6_PRIMARY_WRITE", "NODE-1", "SYNCHRONOUS primary write " + paymentId + " v" + version + " ₹" + r.amount(), ts);

        long latencyMs = -1;
        boolean replicated = false;
        if (replicaWasAlive) {
            appendEvent("E6_REPLICATION_SENT", "NODE-1", "Replication request sent to NODE-2 for " + paymentId, ts);
            long start = System.currentTimeMillis();
            upsertReplica(paymentId, r.amount(), method, "SUCCESS", version, ts);
            latencyMs = System.currentTimeMillis() - start;
            lastLagMs.set(latencyMs);
            logReplication(paymentId, "SYNCHRONOUS", ts, "ACKED", true);
            appendEvent("E6_REPLICA_ACK", "NODE-2", "Replica acknowledged " + paymentId + " in " + latencyMs + " ms", ts);
            setPrimaryReplicationStatus(paymentId, "ACKED");
            appendEvent("E6_COMMIT", "SYSTEM", "Synchronous commit COMMITTED for " + paymentId, ts);
            replicated = true;
        } else {
            logReplication(paymentId, "SYNCHRONOUS", ts, "FAILED", false);
            appendEvent("E6_REPLICA_UNAVAILABLE", "NODE-2", "Replica NODE-2 is unavailable — cannot acknowledge " + paymentId, ts);
            appendEvent("E6_SYNC_COMMIT_BLOCKED", "SYSTEM", "Synchronous commit BLOCKED for " + paymentId + " (no replica ack)", ts);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", paymentId);
        out.put("model", "SYNCHRONOUS");
        out.put("version", version);
        out.put("committed", replicated);
        out.put("status", status);
        out.put("replicated", replicated);
        out.put("latencyMs", latencyMs >= 0 ? latencyMs : null);
        out.put("message", replicated ? "Primary and replica committed" : "Replica unavailable — synchronous commit blocked");
        return out;
    }

    public synchronized Map<String, Object> asyncWrite(Experiment6PaymentRequest r) {
        validate(r);
        String paymentId = resolvePaymentId(r.paymentId());
        String method = r.method().trim();
        long ts = seq.incrementAndGet();
        int version = nextVersion(paymentId);

        upsertPrimary(paymentId, r.amount(), method, "SUCCESS", version, "ASYNCHRONOUS", "PENDING", ts);
        appendEvent("E6_PRIMARY_WRITE", "NODE-1", "ASYNCHRONOUS primary write " + paymentId + " v" + version + " ₹" + r.amount(), ts);
        appendEvent("E6_PRIMARY_COMMIT", "NODE-1", "Primary committed " + paymentId + " without waiting for replica", ts);
        pendingReplications.incrementAndGet();
        appendEvent("E6_REPLICATION_QUEUED", "NODE-1", "Replication for " + paymentId + " queued for background delivery", ts);

        int span = Math.max(0, asyncDelayMaxMs - asyncDelayMinMs);
        int delay = asyncDelayMinMs + (span > 0 ? random.nextInt(span + 1) : 0);
        long primaryCommitAt = System.currentTimeMillis();
        BigDecimal amount = r.amount();
        int finalVersion = version;
        asyncExecutor.schedule(() -> replicateAsync(paymentId, amount, method, finalVersion, primaryCommitAt), delay, TimeUnit.MILLISECONDS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", paymentId);
        out.put("model", "ASYNCHRONOUS");
        out.put("version", version);
        out.put("committed", true);
        out.put("status", "SUCCESS");
        out.put("replicated", false);
        out.put("queuedDelayMs", delay);
        out.put("message", "Primary committed immediately; replica update queued (" + delay + " ms)");
        return out;
    }

    private void replicateAsync(String paymentId, BigDecimal amount, String method, int version, long primaryCommitAt) {
        try {
            synchronized (this) {
                long ts = seq.incrementAndGet();
                if (!replicaAlive) {
                    logReplication(paymentId, "ASYNCHRONOUS", ts, "FAILED", false);
                    appendEvent("E6_REPLICA_UNAVAILABLE", "NODE-2", "Replica unavailable — async replication of " + paymentId + " failed", ts);
                    setPrimaryReplicationStatus(paymentId, "FAILED");
                    return;
                }
                upsertReplica(paymentId, amount, method, "SUCCESS", version, ts);
                long lagMs = System.currentTimeMillis() - primaryCommitAt;
                lastLagMs.set(lagMs);
                logReplication(paymentId, "ASYNCHRONOUS", ts, "ACKED", true);
                setPrimaryReplicationStatus(paymentId, "ACKED");
                appendEvent("E6_REPLICA_WRITE", "NODE-2", "Replica applied " + paymentId + " (v" + version + ")", ts);
                appendEvent("E6_REPLICA_SYNC_COMPLETE", "NODE-2", "Replica sync complete for " + paymentId + " after " + lagMs + " ms", ts);
            }
        } finally {
            pendingReplications.decrementAndGet();
        }
    }

    // ------------------------------------------------------------ controls

    public synchronized Map<String, Object> failReplica() {
        replicaAlive = false;
        appendEvent("E6_REPLICA_FAILED", "NODE-2", "Replica NODE-2 marked unavailable for Experiment 6", seq.incrementAndGet());
        return state();
    }

    public synchronized Map<String, Object> recoverReplica() {
        replicaAlive = true;
        appendEvent("E6_REPLICA_RECOVERED", "NODE-2", "Replica NODE-2 marked online for Experiment 6", seq.incrementAndGet());
        return state();
    }

    public synchronized Map<String, Object> setModel(String model) {
        String m = model == null ? "" : model.trim().toUpperCase(Locale.ROOT);
        if (!m.equals("SYNCHRONOUS") && !m.equals("ASYNCHRONOUS")) throw new IllegalArgumentException("Unknown replication model: " + model);
        activeModel = m;
        appendEvent("E6_MODEL_CHANGED", "SYSTEM", "Active replication model set to " + m, seq.incrementAndGet());
        return state();
    }

    public synchronized Map<String, Object> resync() {
        if (!replicaAlive) throw new IllegalStateException("Replica must be recovered before resync");
        appendEvent("E6_RESYNC_STARTED", "NODE-2", "Resynchronization started from primary", seq.incrementAndGet());
        Map<String, Object> before = computeConsistency();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) before.get("details");
        int resynced = 0;
        for (Map<String, Object> d : details) {
            String type = (String) d.get("type");
            String paymentId = (String) d.get("paymentId");
            if ("EXTRA_ON_REPLICA".equals(type)) continue; // primary is authoritative; extras on replica are left as-is, not deleted
            db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,version,experiment_tag,replicated_at) " +
                            "SELECT payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,version,experiment_tag,NOW() FROM payments WHERE payment_id=? " +
                            "ON DUPLICATE KEY UPDATE amount=VALUES(amount),method=VALUES(method),status=VALUES(status),lamport_ts=VALUES(lamport_ts),version=VALUES(version),replicated_at=NOW()",
                    paymentId);
            logReplication(paymentId, "RESYNC", seq.incrementAndGet(), "ACKED", true);
            appendEvent("E6_RESYNC_RECORD", "NODE-2", "Resynchronized " + paymentId + " (" + type + ")", seq.incrementAndGet());
            resynced++;
        }
        appendEvent("E6_RESYNC_COMPLETED", "NODE-2", "Resynchronization completed: " + resynced + " record(s) updated", seq.incrementAndGet());
        Map<String, Object> after = checkConsistency();
        if (Boolean.TRUE.equals(after.get("consistent"))) {
            appendEvent("E6_CONSISTENCY_RESTORED", "SYSTEM", "Primary and replica are consistent after resynchronization", seq.incrementAndGet());
        }
        after.put("recordsResynced", resynced);
        return after;
    }

    public synchronized Map<String, Object> reset() {
        db.update("DELETE FROM payments WHERE experiment_tag='EXPERIMENT_6'");
        db.update("DELETE FROM dc_paygateway_backup.replica_payments WHERE experiment_tag='EXPERIMENT_6'");
        replicaAlive = true;
        activeModel = "SYNCHRONOUS";
        pendingReplications.set(0);
        lastLagMs.set(0);
        appendEvent("E6_RESET", "SYSTEM", "Experiment 6 state reset", seq.incrementAndGet());
        return state();
    }

    // --------------------------------------------------------------- reads

    public Map<String, Object> state() {
        Map<String, Object> cons = computeConsistency();
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("nodeId", "NODE-1");
        primary.put("alive", true);
        primary.put("recordCount", cons.get("primaryCount"));
        Map<String, Object> replica = new LinkedHashMap<>();
        replica.put("nodeId", "NODE-2");
        replica.put("alive", replicaAlive);
        replica.put("recordCount", cons.get("replicaCount"));
        Map<String, Object> replication = new LinkedHashMap<>();
        replication.put("enabled", true);
        replication.put("model", activeModel);
        replication.put("lagMs", lastLagMs.get());
        replication.put("pendingRecords", pendingReplications.get());
        Map<String, Object> consistency = new LinkedHashMap<>();
        consistency.put("consistent", cons.get("consistent"));
        consistency.put("mismatches", cons.get("mismatches"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experiment", "EXPERIMENT_6");
        out.put("activeModel", activeModel);
        out.put("primary", primary);
        out.put("replica", replica);
        out.put("replication", replication);
        out.put("consistency", consistency);
        return out;
    }

    public synchronized Map<String, Object> checkConsistency() {
        Map<String, Object> result = computeConsistency();
        try {
            String runStatus = Boolean.TRUE.equals(result.get("consistent")) ? "CONSISTENT" : "INCONSISTENT";
            db.update("INSERT INTO reconciliation_runs(scope_name,status,started_at,completed_at) VALUES('EXPERIMENT_6',?,NOW(),NOW())", runStatus);
            Long runId = db.queryForObject("SELECT MAX(run_id) FROM reconciliation_runs", Long.class);
            if (runId != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("details");
                for (Map<String, Object> d : details) {
                    db.update("INSERT INTO reconciliation_mismatches(run_id,payment_id,field_name,primary_value,backup_value,created_at) VALUES(?,?,?,?,?,NOW())",
                            runId, d.get("paymentId"), d.get("type"),
                            String.valueOf(d.getOrDefault("primaryVersion", "")), String.valueOf(d.getOrDefault("replicaVersion", "")));
                }
            }
        } catch (Exception ignored) {
            // reconciliation audit trail is best-effort; never block the consistency result on it
        }
        appendEvent("E6_CONSISTENCY_CHECK", "SYSTEM", "Consistency check: " + result.get("mismatches") + " mismatch(es), consistent=" + result.get("consistent"), seq.incrementAndGet());
        return result;
    }

    public Map<String, Object> payments() {
        List<Map<String, Object>> primary = db.queryForList(
                "SELECT payment_id,order_id,amount,currency,method,status,version,replication_model,replication_status,lamport_ts,created_at,updated_at FROM payments WHERE experiment_tag='EXPERIMENT_6' ORDER BY payment_id");
        List<Map<String, Object>> replica = db.queryForList(
                "SELECT payment_id,order_id,amount,currency,method,status,version,lamport_ts,replicated_at FROM dc_paygateway_backup.replica_payments WHERE experiment_tag='EXPERIMENT_6' ORDER BY payment_id");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primary", primary);
        out.put("replica", replica);
        return out;
    }

    public List<Map<String, Object>> events() {
        return db.queryForList("SELECT event_id,event_type,node_id,message,lamport_ts,created_at FROM system_events WHERE event_type LIKE 'E6_%' ORDER BY event_id DESC LIMIT 100");
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Object> computeConsistency() {
        List<Map<String, Object>> primaryRows = db.queryForList(
                "SELECT payment_id,amount,method,status,version FROM payments WHERE experiment_tag='EXPERIMENT_6' ORDER BY payment_id");
        List<Map<String, Object>> replicaRows = db.queryForList(
                "SELECT payment_id,amount,method,status,version FROM dc_paygateway_backup.replica_payments WHERE experiment_tag='EXPERIMENT_6' ORDER BY payment_id");

        Map<String, Map<String, Object>> primaryById = new LinkedHashMap<>();
        for (Map<String, Object> row : primaryRows) primaryById.put((String) row.get("payment_id"), row);
        Map<String, Map<String, Object>> replicaById = new LinkedHashMap<>();
        for (Map<String, Object> row : replicaRows) replicaById.put((String) row.get("payment_id"), row);

        List<Map<String, Object>> details = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : primaryById.entrySet()) {
            String id = e.getKey();
            Map<String, Object> p = e.getValue();
            Map<String, Object> b = replicaById.get(id);
            if (b == null) {
                details.add(mismatch(id, "MISSING_ON_REPLICA", "Payment " + id + " exists on primary but not on replica"));
                continue;
            }
            List<String> fields = new ArrayList<>();
            if (((Number) p.get("version")).intValue() != ((Number) b.get("version")).intValue()) fields.add("version");
            if (((BigDecimal) p.get("amount")).compareTo((BigDecimal) b.get("amount")) != 0) fields.add("amount");
            if (!Objects.equals(p.get("status"), b.get("status"))) fields.add("status");
            if (!Objects.equals(p.get("method"), b.get("method"))) fields.add("method");
            if (!fields.isEmpty()) {
                String type = fields.contains("version") ? "VERSION_MISMATCH"
                        : fields.contains("amount") ? "AMOUNT_MISMATCH"
                        : fields.contains("status") ? "STATUS_MISMATCH" : "METHOD_MISMATCH";
                Map<String, Object> d = mismatch(id, type, "Fields differ on " + id + ": " + String.join(", ", fields));
                d.put("fields", fields);
                d.put("primaryVersion", p.get("version"));
                d.put("replicaVersion", b.get("version"));
                details.add(d);
            }
        }
        for (String id : replicaById.keySet()) {
            if (!primaryById.containsKey(id)) details.add(mismatch(id, "EXTRA_ON_REPLICA", "Payment " + id + " exists on replica but not on primary"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consistent", details.isEmpty());
        out.put("primaryCount", primaryById.size());
        out.put("replicaCount", replicaById.size());
        out.put("mismatches", details.size());
        out.put("details", details);
        out.put("model", activeModel);
        out.put("replicaAlive", replicaAlive);
        return out;
    }

    private Map<String, Object> mismatch(String paymentId, String type, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", paymentId);
        m.put("type", type);
        m.put("message", message);
        return m;
    }

    private void validate(Experiment6PaymentRequest r) {
        if (r == null || r.amount() == null || r.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (r.method() == null || r.method().isBlank()) throw new IllegalArgumentException("Payment method is required");
    }

    private String resolvePaymentId(String id) {
        if (id == null || id.isBlank()) return nextPaymentId();
        return id.trim().toUpperCase(Locale.ROOT);
    }

    private String nextPaymentId() {
        Integer a = db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(payment_id,5) AS UNSIGNED)),6000) FROM payments WHERE experiment_tag='EXPERIMENT_6'", Integer.class);
        Integer b = db.queryForObject("SELECT COALESCE(MAX(CAST(SUBSTRING(payment_id,5) AS UNSIGNED)),6000) FROM dc_paygateway_backup.replica_payments WHERE experiment_tag='EXPERIMENT_6'", Integer.class);
        int n = Math.max(a == null ? 6000 : a, b == null ? 6000 : b) + 1;
        return "PAY-" + String.format("%04d", n);
    }

    private int nextVersion(String paymentId) {
        List<Integer> v = db.query("SELECT version FROM payments WHERE payment_id=? AND experiment_tag='EXPERIMENT_6'", (rs, i) -> rs.getInt("version"), paymentId);
        return v.isEmpty() ? 1 : v.get(0) + 1;
    }

    private void upsertPrimary(String paymentId, BigDecimal amount, String method, String status, int version, String model, String replicationStatus, long ts) {
        String orderId = "E6-ORD-" + paymentId;
        String shard = Math.floorMod(paymentId.hashCode(), 2) == 0 ? "SHARD-1" : "SHARD-2";
        db.update("INSERT INTO payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,version,experiment_tag,replication_model,replication_status,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
                        "ON DUPLICATE KEY UPDATE amount=VALUES(amount),method=VALUES(method),status=VALUES(status),lamport_ts=VALUES(lamport_ts),version=VALUES(version),replication_model=VALUES(replication_model),replication_status=VALUES(replication_status),updated_at=NOW()",
                paymentId, orderId, "C-1001", amount, "INR", method, status, "E6-" + paymentId, shard, ts, "NODE-1", version, TAG, model, replicationStatus);
    }

    private void upsertReplica(String paymentId, BigDecimal amount, String method, String status, int version, long ts) {
        String orderId = "E6-ORD-" + paymentId;
        String shard = Math.floorMod(paymentId.hashCode(), 2) == 0 ? "SHARD-1" : "SHARD-2";
        db.update("INSERT INTO dc_paygateway_backup.replica_payments(payment_id,order_id,customer_id,amount,currency,method,status,idempotency_key,shard_id,lamport_ts,primary_node_id,version,experiment_tag,replicated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW()) " +
                        "ON DUPLICATE KEY UPDATE amount=VALUES(amount),method=VALUES(method),status=VALUES(status),lamport_ts=VALUES(lamport_ts),version=VALUES(version),replicated_at=NOW()",
                paymentId, orderId, "C-1001", amount, "INR", method, status, "E6-" + paymentId, shard, ts, "NODE-1", version, TAG);
    }

    private void setPrimaryReplicationStatus(String paymentId, String status) {
        db.update("UPDATE payments SET replication_status=? WHERE payment_id=?", status, paymentId);
    }

    private void logReplication(String paymentId, String mode, long sequenceNo, String status, boolean acked) {
        db.update("INSERT INTO replication_log(payment_id,source_node,target_node,mode,sequence_no,status,ack_at,created_at) VALUES(?,?,?,?,?,?,?,NOW())",
                paymentId, "NODE-1", "NODE-2", mode, sequenceNo, status, acked ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
    }

    private void appendEvent(String type, String node, String message, long ts) {
        try {
            db.update("INSERT INTO system_events(event_type,node_id,message,lamport_ts) VALUES(?,?,?,?)", type, node, message, ts);
        } catch (Exception ignored) {
        }
    }
}
