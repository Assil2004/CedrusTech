package com.cedrustech.hrsystem.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * ═══════════════════════════════════════════════════
 * Weeks 10–14 — Event Log (Kafka-equivalent)
 * ═══════════════════════════════════════════════════
 *
 * Every significant system event is written here.
 * This serves three purposes:
 *
 *  1. AUDIT TRAIL  — full history of every chat message,
 *     application, failure, and retry in the system.
 *
 *  2. IDEMPOTENCY  — before processing a chat message,
 *     we check if its correlationId (= ChatMessage.requestId)
 *     was already delivered. Duplicate WebSocket retries
 *     are safe-rejected without double-processing.
 *
 *  3. FAILURE RECOVERY — failed events stay in the log
 *     with status=FAILED and can be replayed or inspected.
 *
 * Event types:
 *   CHAT_RECEIVED    — message arrived at WebSocket handler
 *   CHAT_DELIVERED   — AI reply sent back to client
 *   CHAT_FAILED      — AI call or send failed
 *   CHAT_REJECTED    — backpressure / queue full
 *   APP_RECEIVED     — job application arrived
 *   APP_SAVED        — application persisted to DB
 *   APP_FAILED       — application processing failed
 *   AI_RETRY         — AI call retried after transient error
 */
@Entity
@Table(
    name = "event_log",
    indexes = {
        @Index(name = "idx_correlation_id", columnList = "correlation_id"),
        @Index(name = "idx_session_id",     columnList = "session_id"),
        @Index(name = "idx_event_type",     columnList = "event_type"),
        @Index(name = "idx_timestamp",      columnList = "timestamp")
    }
)
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Weeks 10-14: correlation ID ties frontend → Java → Python together
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    // e.g. CHAT_RECEIVED, CHAT_DELIVERED, APP_SAVED, AI_RETRY
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    // WebSocket session or "rest-client" for HTTP calls
    @Column(name = "session_id", length = 64)
    private String sessionId;

    // Truncated message/reply or error detail
    @Column(name = "payload", length = 1000)
    private String payload;

    // SUCCESS | FAILED | PENDING | RETRIED
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // Which attempt (1 = first try, 2+ = retries)
    @Column(name = "attempt")
    private int attempt;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    public EventLog() {}

    public EventLog(
            String  correlationId,
            String  eventType,
            String  sessionId,
            String  payload,
            String  status,
            int     attempt,
            Instant timestamp) {
        this.correlationId = correlationId;
        this.eventType     = eventType;
        this.sessionId     = sessionId;
        this.payload       = payload != null && payload.length() > 1000
                             ? payload.substring(0, 997) + "…"
                             : payload;
        this.status        = status;
        this.attempt       = attempt;
        this.timestamp     = timestamp;
    }

    // ── Getters ──────────────────────────────────────
    public Long    getId()           { return id; }
    public String  getCorrelationId(){ return correlationId; }
    public String  getEventType()    { return eventType; }
    public String  getSessionId()    { return sessionId; }
    public String  getPayload()      { return payload; }
    public String  getStatus()       { return status; }
    public int     getAttempt()      { return attempt; }
    public Instant getTimestamp()    { return timestamp; }
}
