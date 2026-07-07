package com.cedrustech.hrsystem.service;

import com.cedrustech.hrsystem.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ChatService — orchestrates message processing.
 *
 * Week 3 : ReadWriteLock protects maintenanceMode —
 *          multiple readers (processMessage calls) can
 *          run concurrently; a writer (setMaintenanceMode)
 *          gets exclusive access. No deadlock risk because
 *          read and write locks are never held together.
 *
 * Week 4 : maintenanceMode is also declared volatile.
 *          volatile guarantees visibility across threads
 *          (happens-before on every write). The ReadWriteLock
 *          would suffice alone, but volatile makes the intent
 *          explicit: every thread sees the latest value
 *          immediately without cache inconsistency.
 *
 * Weeks 10-14: correlationId (= ChatMessage.requestId) is
 *          forwarded to AIProxyService so it flows all the
 *          way to Python for end-to-end tracing.
 */
@Service
public class ChatService {

    private static final Logger log =
            LoggerFactory.getLogger(ChatService.class);

    private final AIProxyService aiProxyService;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Week 4 — volatile + ReadWriteLock (belt-and-suspenders):
     *
     * volatile ensures the JVM never caches this field in a
     * CPU register. Any thread that reads maintenanceMode
     * always goes to main memory — guaranteed happens-before
     * with the last write. Combined with the ReadWriteLock this
     * gives both atomicity (lock) and visibility (volatile).
     */
    private volatile boolean maintenanceMode    = false;
    private volatile String  maintenanceMessage =
            "The assistant is temporarily under maintenance.";

    public ChatService(AIProxyService aiProxyService) {
        this.aiProxyService = aiProxyService;
    }

    // ─────────────────────────────────────────────────
    // Process one chat message
    // Weeks 10-14: passes correlationId to AIProxyService
    // ─────────────────────────────────────────────────
    public String processMessage(ChatMessage message) {

        // Read lock — allows concurrent reads, blocks only during writes
        lock.readLock().lock();
        try {
            if (maintenanceMode) return maintenanceMessage;
        } finally {
            lock.readLock().unlock();
        }

        String userText = message.content().trim();

        // Greeting shortcut — no AI call needed
        if (userText.matches("(?i)^(hi|hello|hey|hii|greetings)$")) {
            return "Hello! Welcome to CedrusTech Solutions. How can I help you today?";
        }

        log.info("→ Forwarding to AIProxyService — correlationId={} session={}",
                message.requestId(), message.sessionId());

        // Weeks 10-14: pass requestId as correlationId for full tracing
        String reply = aiProxyService.ask(
                userText,
                message.sessionId(),
                message.requestId()   // correlationId
        );

        if (reply == null || reply.isBlank()) {
            return "I could not find that information in the company knowledge base.";
        }

        return reply;
    }

    // ─────────────────────────────────────────────────
    // Maintenance mode — write lock for exclusive access
    // ─────────────────────────────────────────────────
    public void setMaintenanceMode(boolean enabled, String message) {
        lock.writeLock().lock();
        try {
            maintenanceMode = enabled;
            if (message != null && !message.isBlank()) {
                maintenanceMessage = message;
            }
            log.info("Maintenance mode set to: {}", enabled);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isMaintenanceMode() {
        lock.readLock().lock();
        try {
            return maintenanceMode;
        } finally {
            lock.readLock().unlock();
        }
    }
}