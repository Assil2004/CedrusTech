package com.cedrustech.hrsystem.service;

import com.cedrustech.hrsystem.model.EventLog;
import com.cedrustech.hrsystem.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ═══════════════════════════════════════════════════
 * Weeks 10–14 — Event Log Service
 * ═══════════════════════════════════════════════════
 *
 * Responsibilities:
 *
 *  1. ASYNC LOGGING (fire-and-forget)
 *     All writes run on appExecutor so the main
 *     chat/application flow is never blocked by a
 *     slow DB write. If the write fails it is logged
 *     but does not affect the user response.
 *
 *  2. IDEMPOTENCY GUARD
 *     isAlreadyDelivered(correlationId) checks the
 *     event_log table before processing a chat message.
 *     If CHAT_DELIVERED already exists for that ID,
 *     the message is a duplicate (e.g. WebSocket retry)
 *     and is skipped without double-processing.
 *
 *  3. TRACING SUPPORT
 *     Every event carries the same correlationId
 *     (= ChatMessage.requestId, a UUID per message).
 *     You can query all events for one correlationId
 *     to see the full chain:
 *       CHAT_RECEIVED → AI_RETRY? → CHAT_DELIVERED
 *
 * Event type constants are defined here so all callers
 * use the same strings — no magic literals scattered
 * across the codebase.
 */
@Service
public class EventLogService {

    private static final Logger log =
            LoggerFactory.getLogger(EventLogService.class);

    // ── Event type constants ──────────────────────────
    public static final String CHAT_RECEIVED  = "CHAT_RECEIVED";
    public static final String CHAT_DELIVERED = "CHAT_DELIVERED";
    public static final String CHAT_FAILED    = "CHAT_FAILED";
    public static final String CHAT_REJECTED  = "CHAT_REJECTED";
    public static final String APP_RECEIVED   = "APP_RECEIVED";
    public static final String APP_SAVED      = "APP_SAVED";
    public static final String APP_FAILED     = "APP_FAILED";
    public static final String AI_RETRY       = "AI_RETRY";

    // ── Status constants ──────────────────────────────
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED  = "FAILED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRIED = "RETRIED";

    private final EventLogRepository repo;
    private final ExecutorService    appExecutor;

    public EventLogService(
            EventLogRepository repo,
            @Qualifier("appExecutor") ThreadPoolExecutor appExecutor) {
        this.repo        = repo;
        this.appExecutor = appExecutor;
    }

    // ─────────────────────────────────────────────────
    // Fire-and-forget async log write
    // Non-blocking: caller never waits for DB write
    // ─────────────────────────────────────────────────
    public void logAsync(
            String eventType,
            String correlationId,
            String sessionId,
            String payload,
            String status,
            int    attempt) {

        appExecutor.submit(() -> {
            try {
                EventLog entry = new EventLog(
                        correlationId,
                        eventType,
                        sessionId,
                        payload,
                        status,
                        attempt,
                        Instant.now()
                );
                repo.save(entry);
                log.debug("EVENT LOGGED: type={} correlationId={} status={}",
                        eventType, correlationId, status);

            } catch (Exception e) {
                // Event log failure must NEVER crash the main flow
                log.warn("Event log write failed (non-critical): {} — {}",
                        eventType, e.getMessage());
            }
        });
    }

    // Convenience overload — attempt defaults to 1
    public void logAsync(
            String eventType,
            String correlationId,
            String sessionId,
            String payload,
            String status) {
        logAsync(eventType, correlationId, sessionId, payload, status, 1);
    }

    // ─────────────────────────────────────────────────
    // Idempotency check
    // Returns true if this correlationId was already
    // successfully delivered → caller should skip it.
    // ─────────────────────────────────────────────────
    public boolean isAlreadyDelivered(String correlationId) {
        try {
            boolean exists = repo.existsByCorrelationIdAndEventType(
                    correlationId, CHAT_DELIVERED);
            if (exists) {
                log.warn("IDEMPOTENCY: duplicate message detected — " +
                         "correlationId={} already delivered, skipping.",
                        correlationId);
            }
            return exists;
        } catch (Exception e) {
            // If DB is down, allow processing (fail-open)
            log.warn("Idempotency check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }
}