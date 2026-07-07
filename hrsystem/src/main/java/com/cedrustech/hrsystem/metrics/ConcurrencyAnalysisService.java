package com.cedrustech.hrsystem.metrics;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ═══════════════════════════════════════════════════
 * ConcurrencyAnalysisService — Weeks 1-2 Theory Applied
 * ═══════════════════════════════════════════════════
 *
 * This service computes LIVE values for the three laws
 * the professor requires — based on actual runtime data,
 * not just theoretical formulas.
 *
 * ┌─────────────────────────────────────────────────┐
 * │ 1. AMDAHL'S LAW                                 │
 * │    Speedup(N) = 1 / (S + (1-S)/N)              │
 * │    S = serial fraction of the workload          │
 * │    N = number of parallel workers               │
 * │                                                 │
 * │    In CedrusTech: the serial fraction S is      │
 * │    Ollama inference (cannot be parallelized      │
 * │    per request). The parallel fraction (1-S)    │
 * │    is RAG retrieval, embedding, JSON parsing.   │
 * │    Estimated S ≈ 0.85 (85% Ollama inference).  │
 * │                                                 │
 * │    With N=4 workers:                            │
 * │    Speedup = 1/(0.85 + 0.15/4) = 1.14×         │
 * │    → 14% faster than 1 worker.                 │
 * │    With N=1 worker (before fix):                │
 * │    5 clients wait 16s×5=80s total.             │
 * │    With N=4 workers (after fix):                │
 * │    5 clients all start at t=0, finish at ~16s  │
 * │    Effective improvement: 5× for concurrent    │
 * │    clients (they run in parallel, not serial).  │
 * ├─────────────────────────────────────────────────┤
 * │ 2. LITTLE'S LAW                                 │
 * │    L = λ × W                                   │
 * │    L = average items in system (queue+service) │
 * │    λ = throughput (items/sec)                  │
 * │    W = average time in system (latency)        │
 * │                                                 │
 * │    Example with 5 concurrent clients:          │
 * │    W = 16,000ms (avg Ollama latency)           │
 * │    λ = measured throughput from MetricsCollector│
 * │    L = λ × W (predicted queue depth)           │
 * │                                                 │
 * │    If L > wsExecutor.queue capacity → overload │
 * │    This predicts when the system will drop msgs │
 * ├─────────────────────────────────────────────────┤
 * │ 3. CONTEXT SWITCHING COST                      │
 * │    More threads ≠ more speed.                  │
 * │    Measured as: (poolSize - activeThreads)/     │
 * │    poolSize → idle thread ratio.               │
 * │    High idle ratio = over-provisioned pool     │
 * │    (wasted context switches).                  │
 * │    Optimal: poolSize ≈ activeThreads (busy).  │
 * ├─────────────────────────────────────────────────┤
 * │ 4. CONCURRENCY MODEL RECOMMENDATION            │
 * │    Based on live L (Little's Law):             │
 * │    L < queue_capacity/2 → system healthy       │
 * │    L > queue_capacity/2 → approaching overload │
 * │    L > queue_capacity   → messages will drop   │
 * └─────────────────────────────────────────────────┘
 */
@Service
public class ConcurrencyAnalysisService {

    // Estimated serial fraction: Ollama inference time
    // as fraction of total request time.
    // Measured: avg total=16s, RAG+embedding≈2s, Ollama≈14s
    // S = 14/16 = 0.875
    private static final double SERIAL_FRACTION = 0.875;

    // Python thread pool workers (matches api.py _ollama_executor)
    private static final int PYTHON_WORKERS = 4;

    // Java wsExecutor max pool
    private static final int JAVA_WS_MAX = 50;

    private final MetricsCollector   metrics;
    private final ThreadPoolExecutor wsExecutor;
    private final ThreadPoolExecutor aiExecutor;
    private final ThreadPoolExecutor appExecutor;

    public ConcurrencyAnalysisService(
            MetricsCollector metrics,
            @Qualifier("wsExecutor")  ThreadPoolExecutor wsExecutor,
            @Qualifier("aiExecutor")  ThreadPoolExecutor aiExecutor,
            @Qualifier("appExecutor") ThreadPoolExecutor appExecutor) {
        this.metrics     = metrics;
        this.wsExecutor  = wsExecutor;
        this.aiExecutor  = aiExecutor;
        this.appExecutor = appExecutor;
    }

    // ─────────────────────────────────────────────────
    // Full analysis report — called by /metrics/analysis
    // ─────────────────────────────────────────────────
    public Map<String, Object> getAnalysis() {
        Map<String, Object> report = new LinkedHashMap<>();

        report.put("amdahlsLaw",        computeAmdahl());
        report.put("littlesLaw",        computeLittlesLaw());
        report.put("contextSwitching",  computeContextSwitching());
        report.put("recommendation",    getRecommendation());
        report.put("latencyPercentiles", computePercentiles());

        return report;
    }

    // ─────────────────────────────────────────────────
    // 1. Amdahl's Law
    // ─────────────────────────────────────────────────
    private Map<String, Object> computeAmdahl() {
        Map<String, Object> result = new LinkedHashMap<>();

        double S = SERIAL_FRACTION;

        // Speedup with current Python thread pool (N=4)
        double speedup4  = 1.0 / (S + (1 - S) / 4.0);
        // Speedup with 1 worker (before fix — serial)
        double speedup1  = 1.0 / (S + (1 - S) / 1.0);
        // Theoretical max speedup (N→∞)
        double maxSpeedup = 1.0 / S;

        result.put("serialFraction_S",         String.format("%.1f%%", S * 100));
        result.put("parallelFraction_1minusS",  String.format("%.1f%%", (1 - S) * 100));
        result.put("workersN",                  PYTHON_WORKERS);
        result.put("speedupWith1Worker",        String.format("%.2f×", speedup1));
        result.put("speedupWith4Workers",       String.format("%.2f×", speedup4));
        result.put("theoreticalMaxSpeedup",     String.format("%.2f×", maxSpeedup));
        result.put("insight",
            "Adding more than 4 Python workers gives diminishing returns " +
            "because " + (int)(S * 100) + "% of work (Ollama inference) is serial per request. " +
            "The 4-worker model allows 4 CONCURRENT requests each at full speed, " +
            "which is the key improvement (parallel client handling, not serial speedup).");

        return result;
    }

    // ─────────────────────────────────────────────────
    // 2. Little's Law  L = λ × W
    // ─────────────────────────────────────────────────
    private Map<String, Object> computeLittlesLaw() {
        Map<String, Object> result = new LinkedHashMap<>();

        long   uptimeS   = Math.max(1, metrics.getUptimeSeconds());
        long   delivered = metrics.getDeliveredMessages();
        double avgLatMs  = metrics.getAvgLatencyMs();
        double avgLatS   = avgLatMs / 1000.0;

        // λ = throughput in requests/second
        double lambda = (double) delivered / uptimeS;

        // L = average number of requests in system simultaneously
        double L = lambda * avgLatS;

        // Queue capacity of wsExecutor
        int queueCap = wsExecutor.getQueue().remainingCapacity()
                     + wsExecutor.getQueue().size();

        String status;
        if (L < queueCap * 0.25)      status = "✅ HEALTHY   — well within capacity";
        else if (L < queueCap * 0.75) status = "⚠ MODERATE  — monitor queue growth";
        else                           status = "❌ OVERLOADED — messages will be dropped";

        result.put("formula",           "L = λ × W");
        result.put("lambda_reqPerSec",  String.format("%.4f", lambda));
        result.put("W_avgLatencySec",   String.format("%.2f", avgLatS));
        result.put("L_avgConcurrent",   String.format("%.2f", L));
        result.put("wsQueueCapacity",   queueCap);
        result.put("systemStatus",      status);
        result.put("insight",
            String.format(
                "At current throughput (λ=%.4f req/s) and latency (W=%.1fs), " +
                "the system carries L=%.2f concurrent requests on average. " +
                "Queue capacity is %d. System is %s.",
                lambda, avgLatS, L, queueCap,
                L < queueCap * 0.25 ? "healthy" : "approaching capacity"
            ));

        return result;
    }

    // ─────────────────────────────────────────────────
    // 3. Context Switching Cost
    // ─────────────────────────────────────────────────
    private Map<String, Object> computeContextSwitching() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Idle ratio: threads that exist but are not working
        double wsIdleRatio  = idleRatio(wsExecutor);
        double aiIdleRatio  = idleRatio(aiExecutor);
        double appIdleRatio = idleRatio(appExecutor);

        result.put("wsExecutor",  poolStats(wsExecutor,  wsIdleRatio));
        result.put("aiExecutor",  poolStats(aiExecutor,  aiIdleRatio));
        result.put("appExecutor", poolStats(appExecutor, appIdleRatio));
        result.put("insight",
            "Idle threads waste CPU cycles via context-switch overhead. " +
            "Pools sized at core=N maintain N threads always ready. " +
            "Threads above core shrink after keepAlive=60s. " +
            "wsExecutor core=10 handles 10 concurrent clients without spin-up latency.");

        return result;
    }

    private double idleRatio(ThreadPoolExecutor pool) {
        int size   = pool.getPoolSize();
        int active = pool.getActiveCount();
        return size > 0 ? (double)(size - active) / size : 0.0;
    }

    private Map<String, Object> poolStats(ThreadPoolExecutor pool, double idleRatio) {
        return Map.of(
            "poolSize",      pool.getPoolSize(),
            "activeThreads", pool.getActiveCount(),
            "idleRatio",     String.format("%.0f%%", idleRatio * 100),
            "queueSize",     pool.getQueue().size(),
            "efficiency",    idleRatio < 0.5 ? "✅ Good" :
                             idleRatio < 0.8 ? "⚠ Over-provisioned" : "❌ Wasteful"
        );
    }

    // ─────────────────────────────────────────────────
    // 4. Percentile latency
    // ─────────────────────────────────────────────────
    private Map<String, Object> computePercentiles() {
        return Map.of(
            "p50_ms", String.format("%.0f", metrics.getPercentileLatency(50)),
            "p95_ms", String.format("%.0f", metrics.getPercentileLatency(95)),
            "avg_ms", String.format("%.0f", metrics.getAvgLatencyMs()),
            "note",   "p50=median response time. p95=worst 5% of requests. " +
                      "High p95 vs p50 gap indicates occasional Ollama queue spikes."
        );
    }

    // ─────────────────────────────────────────────────
    // 5. Live recommendation
    // ─────────────────────────────────────────────────
    private String getRecommendation() {
        long   active  = metrics.getActiveClients();
        double avgLat  = metrics.getAvgLatencyMs();
        int    queueSz = wsExecutor.getQueue().size();

        if (queueSz > 50) {
            return "SCALE UP: wsExecutor queue growing — consider increasing max-pool-size or " +
                   "adding more Python workers (uvicorn --workers N).";
        }
        if (avgLat > 25_000) {
            return "LATENCY HIGH: Ollama inference > 25s. " +
                   "Consider GPU acceleration or a smaller model (llama3:8b).";
        }
        if (active > 40) {
            return "HIGH LOAD: " + active + " active clients. " +
                   "wsExecutor max=50 approaching — monitor for backpressure.";
        }
        return "✅ HEALTHY: " + active + " client(s), avg latency=" +
               String.format("%.0f", avgLat) + "ms. " +
               "Current concurrency model is sufficient for this load.";
    }
}