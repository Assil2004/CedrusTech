package com.cedrustech.hrsystem.repository;

import com.cedrustech.hrsystem.model.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Weeks 10–14: Event log queries.
 *
 * existsByCorrelationIdAndEventType → idempotency check
 *   Before processing a chat message, we verify its
 *   correlationId was not already CHAT_DELIVERED.
 *   If it was → duplicate → skip silently.
 */
@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    // Idempotency: was this correlationId already processed?
    boolean existsByCorrelationIdAndEventType(
            String correlationId,
            String eventType
    );

    // Tracing: get full chain for one correlationId
    List<EventLog> findByCorrelationIdOrderByTimestampAsc(
            String correlationId
    );

    // Session audit: all events for one WebSocket session
    List<EventLog> findBySessionIdOrderByTimestampDesc(
            String sessionId
    );
}