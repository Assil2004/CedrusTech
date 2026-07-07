package com.cedrustech.hrsystem.websocket;

import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.ChatMessage;
import com.cedrustech.hrsystem.queue.BoundedMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SessionManager — thread-safe registry of all WebSocket sessions.
 *
 * Maintains:
 *   - sessions   : sessionId → WebSocketSession
 *   - queues     : sessionId → BoundedMessageQueue (backpressure)
 *   - histories  : sessionId → chat history (last 20 messages)
 *
 * All maps use ConcurrentHashMap for lock-free concurrent access.
 */
@Component
public class SessionManager {

    private static final Logger log =
            LoggerFactory.getLogger(SessionManager.class);

    private static final int MAX_HISTORY = 20;

    private final ConcurrentHashMap<String, WebSocketSession>              sessions  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BoundedMessageQueue>           queues    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> histories = new ConcurrentHashMap<>();

    private final MetricsCollector metrics;

    public SessionManager(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    // ── Registration ─────────────────────────────────
    public void register(WebSocketSession session) {
        String id = session.getId();
        sessions .put(id, session);
        queues   .put(id, new BoundedMessageQueue(id, metrics));
        histories.put(id, new CopyOnWriteArrayList<>());
        metrics.clientConnected();
        log.info("Session registered: {} | Active: {}", id, sessions.size());
    }

    public void remove(WebSocketSession session) {
        String id = session.getId();
        sessions .remove(id);
        queues   .remove(id);
        histories.remove(id);
        metrics.clientDisconnected();
        log.info("Session removed: {} | Active: {}", id, sessions.size());
    }

    // ── Lookups ──────────────────────────────────────
    public WebSocketSession     getSession(String id) { return sessions.get(id); }
    public BoundedMessageQueue  getQueue(String id)   { return queues.get(id);   }

    public List<ChatMessage> getHistory(String id) {
        return histories.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
    }

    // ── Chat history ─────────────────────────────────
    public void appendToHistory(String id, ChatMessage message) {
        CopyOnWriteArrayList<ChatMessage> history =
                histories.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        history.add(message);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    // ── Info ─────────────────────────────────────────
    public Collection<WebSocketSession> getAllSessions()   { return sessions.values(); }
    public int     getActiveCount()                        { return sessions.size();    }
    public boolean contains(String id)                    { return sessions.containsKey(id); }
}