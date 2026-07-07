package com.cedrustech.hrsystem.websocket;

import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.ApiResponse;
import com.cedrustech.hrsystem.model.ChatMessage;
import com.cedrustech.hrsystem.queue.BoundedMessageQueue;
import com.cedrustech.hrsystem.service.ChatService;
import com.cedrustech.hrsystem.service.EventLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * ChatWebSocketHandler — handles all WebSocket messages.
 *
 * Weeks 10-14 additions:
 *   - IDEMPOTENCY: checks event_log before processing;
 *     duplicate messages (browser retry on reconnect)
 *     are rejected without double-processing.
 *   - TRACING: correlationId (= msg.requestId()) logged
 *     at RECEIVED, DELIVERED, FAILED, and REJECTED events.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionManager   sessionManager;
    private final ChatService      chatService;
    private final MetricsCollector metrics;
    private final EventLogService  eventLogService;
    private final ExecutorService  wsExecutor;
    private final ObjectMapper     objectMapper;

    public ChatWebSocketHandler(
            SessionManager sessionManager,
            ChatService chatService,
            MetricsCollector metrics,
            EventLogService eventLogService,
            @Qualifier("wsExecutor") ThreadPoolExecutor wsExecutor) {
        this.sessionManager = sessionManager;
        this.chatService    = chatService;
        this.metrics        = metrics;
        this.eventLogService = eventLogService;
        this.wsExecutor     = wsExecutor;
        this.objectMapper   = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionManager.register(session);
        log.info("✅ Client connected: {} | Active: {}",
                session.getId(), sessionManager.getActiveCount());

        sendText(session, toJson(ApiResponse.ok(
            "Connected to CedrusTech AI Assistant",
            Map.of("sessionId", session.getId())
        )));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage rawMessage) {
        metrics.messageReceived();
        long startMs = System.currentTimeMillis();

        String userText = rawMessage.getPayload().trim();
        if (userText.isBlank()) {
            sendError(session, "Message must not be empty.");
            return;
        }

        ChatMessage msg = ChatMessage.ofUser(session.getId(), userText);
        String correlationId = msg.requestId(); // UUID per message = tracing ID

        // ── Weeks 10-14: Idempotency check ──────────
        // If browser retried and this message was already delivered,
        // skip it silently to prevent duplicate AI calls.
        if (eventLogService.isAlreadyDelivered(correlationId)) {
            log.info("IDEMPOTENCY: skipping duplicate — correlationId={}", correlationId);
            return;
        }

        // ── Backpressure: bounded queue ──────────────
        BoundedMessageQueue queue = sessionManager.getQueue(session.getId());
        if (queue == null || !queue.offer(msg)) {
            sendError(session, "Server busy — please wait a moment.");
            metrics.requestRejected();
            eventLogService.logAsync(
                    EventLogService.CHAT_REJECTED,
                    correlationId, session.getId(),
                    userText.length() > 100 ? userText.substring(0, 100) : userText,
                    EventLogService.STATUS_FAILED
            );
            log.warn("BACKPRESSURE: session {} queue full", session.getId());
            return;
        }

        // Weeks 10-14: log CHAT_RECEIVED with correlationId
        eventLogService.logAsync(
                EventLogService.CHAT_RECEIVED,
                correlationId, session.getId(),
                userText.length() > 200 ? userText.substring(0, 200) : userText,
                EventLogService.STATUS_PENDING
        );

        log.info("📨 [{}] correlationId={} → \"{}\"",
                session.getId(), correlationId,
                userText.length() > 60 ? userText.substring(0, 60) + "…" : userText);

        // ── Async AI call on wsExecutor ──────────────
        CompletableFuture
            .supplyAsync(() -> chatService.processMessage(msg), wsExecutor)
            .orTimeout(30, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex.getCause() instanceof TimeoutException) {
                    log.warn("⏱ AI timeout — correlationId={}", correlationId);
                } else {
                    log.error("💥 AI error — correlationId={}: {}", correlationId, ex.getMessage());
                }
                metrics.requestFailed();
                // Weeks 10-14: log failure
                eventLogService.logAsync(
                        EventLogService.CHAT_FAILED,
                        correlationId, session.getId(),
                        ex.getMessage(),
                        EventLogService.STATUS_FAILED
                );
                return "I could not find that information in the company knowledge base.";
            })
            .thenAccept(reply -> {
                sessionManager.appendToHistory(session.getId(), msg);
                sessionManager.appendToHistory(session.getId(),
                    ChatMessage.ofAssistant(session.getId(), reply));

                sendText(session, toJson(ApiResponse.ok(
                    "Chat reply generated",
                    Map.of(
                        "reply",         reply,
                        "requestId",     correlationId,  // client can use for tracing
                        "correlationId", correlationId
                    )
                )));

                metrics.messageDelivered();
                metrics.recordLatency(System.currentTimeMillis() - startMs);

                // Weeks 10-14: log successful delivery
                eventLogService.logAsync(
                        EventLogService.CHAT_DELIVERED,
                        correlationId, session.getId(),
                        reply.length() > 200 ? reply.substring(0, 200) : reply,
                        EventLogService.STATUS_SUCCESS
                );
            })
            .whenComplete((result, ex) -> {
                try {
                    if (queue != null) queue.poll(0, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.remove(session);
        log.info("❌ Client disconnected: {} | Reason: {} | Active: {}",
            session.getId(), status.getReason(), sessionManager.getActiveCount());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error on {}: {}", session.getId(), exception.getMessage());
        sessionManager.remove(session);
        metrics.requestFailed();
    }

    private void sendText(WebSocketSession session, String json) {
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.error("Send failed on {}: {}", session.getId(), e.getMessage());
                metrics.requestFailed();
            }
        }
    }

    private void sendError(WebSocketSession session, String errorMsg) {
        sendText(session, toJson(ApiResponse.error(errorMsg,
                Map.of("reply", errorMsg))));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}