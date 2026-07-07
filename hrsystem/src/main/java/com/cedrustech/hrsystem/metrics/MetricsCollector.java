package com.cedrustech.hrsystem.metrics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * MetricsCollector — all system observability data.
 *
 * Week 4 — Atomic visibility:
 *   LongAdder  : high-throughput increment (no contention)
 *   AtomicLong : live gauge values (compareAndSet semantics)
 *
 * Week 1-2 / Amdahl + Little's Law additions:
 *   p50 / p95 latency  : sliding window of last 1000 samples
 *   Little's Law live  : L = λ × W computed from live metrics
 *   Amdahl speedup     : theoretical speedup vs serial baseline
 *
 * Why ConcurrentLinkedDeque for latency samples?
 *   Thread-safe, lock-free, O(1) add/remove at both ends.
 *   We cap at MAX_SAMPLES and evict oldest on overflow.
 */
@Component
public class MetricsCollector {

    // ── Message counters ──────────────────────────────
    private final LongAdder totalMessagesReceived  = new LongAdder();
    private final LongAdder totalMessagesDelivered = new LongAdder();
    private final LongAdder droppedMessages        = new LongAdder();

    // ── Request counters ──────────────────────────────
    private final LongAdder failedRequests   = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();

    // ── Application counters ──────────────────────────
    private final LongAdder totalApplications      = new LongAdder();
    private final LongAdder successfulApplications = new LongAdder();
    private final LongAdder failedApplications     = new LongAdder();

    // ── Latency — average ─────────────────────────────
    private final LongAdder totalLatencyMs = new LongAdder();
    private final LongAdder latencySamples = new LongAdder();

    // ── Latency — percentile (sliding window, last 1000) ─
    private static final int MAX_LATENCY_SAMPLES = 1000;
    private final ConcurrentLinkedDeque<Long> recentLatencies =
            new ConcurrentLinkedDeque<>();

    // ── Live gauges ───────────────────────────────────
    private final AtomicLong activeClients  = new AtomicLong(0);
    private final AtomicLong chatQueueDepth = new AtomicLong(0);
    private final AtomicLong aiQueueDepth   = new AtomicLong(0);

    private final long startTimeMs = System.currentTimeMillis();

    // ── Client lifecycle ─────────────────────────────
    public void clientConnected()    { activeClients.incrementAndGet(); }
    public void clientDisconnected() { activeClients.decrementAndGet(); }

    // ── Messages ─────────────────────────────────────
    public void messageReceived()  { totalMessagesReceived.increment(); }
    public void messageDelivered() { totalMessagesDelivered.increment(); }
    public void messageDropped()   { droppedMessages.increment(); }

    // ── Requests ─────────────────────────────────────
    public void requestFailed()   { failedRequests.increment(); }
    public void requestRejected() { rejectedRequests.increment(); }

    // ── Applications ─────────────────────────────────
    public void applicationReceived()  { totalApplications.increment(); }
    public void applicationSucceeded() { successfulApplications.increment(); }
    public void applicationFailed()    { failedApplications.increment(); }

    // ── Latency ──────────────────────────────────────
    public void recordLatency(long latencyMs) {
        totalLatencyMs.add(latencyMs);
        latencySamples.increment();

        // Sliding window for percentiles
        recentLatencies.addLast(latencyMs);
        // Evict oldest if over capacity (approximate, lock-free)
        while (recentLatencies.size() > MAX_LATENCY_SAMPLES) {
            recentLatencies.pollFirst();
        }
    }

    // ── Queue depth ───────────────────────────────────
    public void updateChatQueueDepth(int depth) { chatQueueDepth.set(depth); }
    public void updateAiQueueDepth(int depth)   { aiQueueDepth.set(depth);   }

    // ─────────────────────────────────────────────────
    // Percentile latency (p50, p95)
    //
    // Algorithm: copy snapshot → sort → index by percentile
    // Not lock-free but called infrequently (metrics endpoint).
    // ─────────────────────────────────────────────────
    public double getPercentileLatency(double percentile) {
        List<Long> snapshot = new ArrayList<>(recentLatencies);
        if (snapshot.isEmpty()) return 0.0;
        Collections.sort(snapshot);
        // Ceiling index: p50 of 10 samples → index 5
        int idx = (int) Math.ceil(percentile / 100.0 * snapshot.size()) - 1;
        return snapshot.get(Math.max(0, Math.min(idx, snapshot.size() - 1)));
    }

    // ─────────────────────────────────────────────────
    // Full snapshot (called by MetricsController)
    // ─────────────────────────────────────────────────
    public Map<String, Object> getSnapshot() {

        long uptimeSeconds     = (System.currentTimeMillis() - startTimeMs) / 1000;
        long deliveredMessages = totalMessagesDelivered.sum();
        long sampleCount       = latencySamples.sum();
        double throughput      = uptimeSeconds > 0
                ? (double) deliveredMessages / uptimeSeconds : 0.0;
        double avgLatency      = sampleCount > 0
                ? (double) totalLatencyMs.sum() / sampleCount : 0.0;

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("timestamp",              Instant.now().toString());
        snapshot.put("uptimeSeconds",          uptimeSeconds);
        snapshot.put("activeClients",          activeClients.get());
        snapshot.put("chatQueueDepth",         chatQueueDepth.get());
        snapshot.put("aiQueueDepth",           aiQueueDepth.get());
        snapshot.put("totalMessagesReceived",  totalMessagesReceived.sum());
        snapshot.put("totalMessagesDelivered", deliveredMessages);
        snapshot.put("droppedMessages",        droppedMessages.sum());
        snapshot.put("failedRequests",         failedRequests.sum());
        snapshot.put("rejectedRequests",       rejectedRequests.sum());
        snapshot.put("totalApplications",      totalApplications.sum());
        snapshot.put("successfulApplications", successfulApplications.sum());
        snapshot.put("failedApplications",     failedApplications.sum());
        snapshot.put("throughputMsgPerSec",    throughput);
        snapshot.put("avgLatencyMs",           avgLatency);

        // p50 / p95 — Week 1-2 Amdahl/Little additions
        snapshot.put("p50LatencyMs",           getPercentileLatency(50));
        snapshot.put("p95LatencyMs",           getPercentileLatency(95));
        snapshot.put("latencySampleCount",     sampleCount);

        return snapshot;
    }

    // ── Accessors for ConcurrencyAnalysisService ──────
    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }
    public long getDeliveredMessages() { return totalMessagesDelivered.sum(); }
    public long getActiveClients()     { return activeClients.get(); }
    public double getAvgLatencyMs() {
        long s = latencySamples.sum();
        return s > 0 ? (double) totalLatencyMs.sum() / s : 0.0;
    }
}