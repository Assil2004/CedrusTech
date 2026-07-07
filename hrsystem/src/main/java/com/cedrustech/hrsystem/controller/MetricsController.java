package com.cedrustech.hrsystem.controller;

import com.cedrustech.hrsystem.metrics.ConcurrencyAnalysisService;
import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.EventLog;
import com.cedrustech.hrsystem.repository.EventLogRepository;
import com.cedrustech.hrsystem.websocket.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Metrics Endpoints
 *
 * GET /metrics              — full snapshot (throughput, latency, queues, p50, p95)
 * GET /metrics/pools        — executor thread pool details
 * GET /metrics/health       — simple UP/DOWN
 * GET /metrics/analysis     — Amdahl's Law, Little's Law, context switching (NEW)
 * GET /metrics/trace/{id}   — distributed trace for one correlationId
 * GET /metrics/session/{id} — audit trail for one WebSocket session
 */
@RestController
@CrossOrigin(origins = "*")
public class MetricsController {

    private static final Logger log =
            LoggerFactory.getLogger(MetricsController.class);

    private final MetricsCollector          metricsCollector;
    private final ConcurrencyAnalysisService analysisService;
    private final SessionManager            sessionManager;
    private final EventLogRepository        eventLogRepository;
    private final ThreadPoolExecutor        wsExecutor;
    private final ThreadPoolExecutor        aiExecutor;
    private final ThreadPoolExecutor        appExecutor;

    public MetricsController(
            MetricsCollector metricsCollector,
            ConcurrencyAnalysisService analysisService,
            SessionManager sessionManager,
            EventLogRepository eventLogRepository,
            @Qualifier("wsExecutor")  ThreadPoolExecutor wsExecutor,
            @Qualifier("aiExecutor")  ThreadPoolExecutor aiExecutor,
            @Qualifier("appExecutor") ThreadPoolExecutor appExecutor) {
        this.metricsCollector   = metricsCollector;
        this.analysisService    = analysisService;
        this.sessionManager     = sessionManager;
        this.eventLogRepository = eventLogRepository;
        this.wsExecutor         = wsExecutor;
        this.aiExecutor         = aiExecutor;
        this.appExecutor        = appExecutor;
    }

    // ─────────────────────────────────────────────────
    // GET /metrics — full snapshot including p50/p95
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        metricsCollector.updateChatQueueDepth(wsExecutor.getQueue().size());
        metricsCollector.updateAiQueueDepth(aiExecutor.getQueue().size());

        Map<String, Object> snapshot =
                new LinkedHashMap<>(metricsCollector.getSnapshot());
        snapshot.put("activeClientsLive", sessionManager.getActiveCount());
        snapshot.put("executorPools",     buildPoolStats());
        snapshot.put("totalEventLogs",    eventLogRepository.count());

        return ResponseEntity.ok(snapshot);
    }

    // ─────────────────────────────────────────────────
    // GET /metrics/analysis — Amdahl, Little's Law, context switching
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics/analysis")
    public ResponseEntity<Map<String, Object>> getConcurrencyAnalysis() {
        return ResponseEntity.ok(analysisService.getAnalysis());
    }

    // ─────────────────────────────────────────────────
    // GET /metrics/pools
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics/pools")
    public ResponseEntity<Map<String, Object>> getPoolMetrics() {
        return ResponseEntity.ok(buildPoolStats());
    }

    // ─────────────────────────────────────────────────
    // GET /metrics/health
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(Map.of(
            "status",        "UP",
            "activeClients", sessionManager.getActiveCount(),
            "wsPoolActive",  wsExecutor.getActiveCount(),
            "aiPoolActive",  aiExecutor.getActiveCount()
        ));
    }

    // ─────────────────────────────────────────────────
    // GET /metrics/trace/{correlationId}
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics/trace/{correlationId}")
    public ResponseEntity<List<EventLog>> getTrace(
            @PathVariable String correlationId) {
        return ResponseEntity.ok(
            eventLogRepository.findByCorrelationIdOrderByTimestampAsc(correlationId)
        );
    }

    // ─────────────────────────────────────────────────
    // GET /metrics/session/{sessionId}
    // ─────────────────────────────────────────────────
    @GetMapping("/metrics/session/{sessionId}")
    public ResponseEntity<List<EventLog>> getSessionAudit(
            @PathVariable String sessionId) {
        return ResponseEntity.ok(
            eventLogRepository.findBySessionIdOrderByTimestampDesc(sessionId)
        );
    }

    // ─────────────────────────────────────────────────
    // Pool stats helper
    // ─────────────────────────────────────────────────
    private Map<String, Object> buildPoolStats() {
        Map<String, Object> pools = new LinkedHashMap<>();

        pools.put("wsExecutor",  poolMap(wsExecutor));
        pools.put("aiExecutor",  poolMap(aiExecutor));
        pools.put("appExecutor", poolMap(appExecutor));

        return pools;
    }

    private Map<String, Object> poolMap(ThreadPoolExecutor p) {
        return Map.of(
            "corePoolSize",   p.getCorePoolSize(),
            "maxPoolSize",    p.getMaximumPoolSize(),
            "activeThreads",  p.getActiveCount(),
            "poolSize",       p.getPoolSize(),
            "queueSize",      p.getQueue().size(),
            "queueRemaining", p.getQueue().remainingCapacity(),
            "completedTasks", p.getCompletedTaskCount(),
            "totalTasks",     p.getTaskCount()
        );
    }
}