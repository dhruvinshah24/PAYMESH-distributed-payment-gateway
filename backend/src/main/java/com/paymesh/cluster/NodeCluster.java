package com.paymesh.cluster;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process control-plane model used by the UI and payment service.
 * The same state transitions are exposed as explicit controls so the viva
 * demonstration can show failure detection, Bully election and recovery.
 */
public final class NodeCluster implements AutoCloseable {
    public record Node(String id, int priority, boolean alive, String role, long lastHeartbeatEpochMs) {}

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Integer> priority = Map.of("NODE-1", 40, "NODE-2", 30);
    private final Map<String, Boolean> alive = new ConcurrentHashMap<>();
    private final Map<String, Long> heartbeat = new ConcurrentHashMap<>();
    private final AtomicLong electionSequence = new AtomicLong();
    private volatile String coordinator = "NODE-1";
    private volatile String primary = "NODE-1";

    public NodeCluster() {
        alive.put("NODE-1", true); alive.put("NODE-2", true);
        long now = System.currentTimeMillis(); heartbeat.put("NODE-1", now); heartbeat.put("NODE-2", now);
        scheduler.scheduleAtFixedRate(this::heartbeatTick, 1, 1, TimeUnit.SECONDS);
    }

    private void heartbeatTick() {
        String c = coordinator;
        if (!Boolean.TRUE.equals(alive.get(c))) elect();
        else heartbeat.put(c, System.currentTimeMillis());
    }

    public synchronized String elect() {
        String winner = alive.entrySet().stream().filter(Map.Entry::getValue)
                .max(Comparator.comparingInt(e -> priority.getOrDefault(e.getKey(), 0)))
                .map(Map.Entry::getKey).orElse("NONE");
        coordinator = winner;
        if (!"NONE".equals(winner)) primary = winner;
        electionSequence.incrementAndGet();
        return winner;
    }

    public void fail(String node) { alive.put(node, false); if (node.equals(coordinator)) elect(); }
    public void recover(String node) { alive.put(node, true); heartbeat.put(node, System.currentTimeMillis()); }

    public Map<String, Object> snapshot() {
        List<Node> nodes = priority.keySet().stream().sorted().map(id -> new Node(
                id, priority.get(id), Boolean.TRUE.equals(alive.get(id)),
                id.equals(primary) ? "PRIMARY" : "BACKUP", heartbeat.getOrDefault(id, 0L))).toList();
        return new LinkedHashMap<>(Map.of(
                "coordinator", coordinator,
                "activePrimary", primary,
                "elections", electionSequence.get(),
                "heartbeatIntervalMs", 1000,
                "timestamp", Instant.now().toString(),
                "nodes", nodes
        ));
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
