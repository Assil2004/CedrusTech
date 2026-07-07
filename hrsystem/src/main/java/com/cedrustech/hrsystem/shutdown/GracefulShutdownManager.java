package com.cedrustech.hrsystem.shutdown;

import com.cedrustech.hrsystem.websocket.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * GracefulShutdownManager — orderly shutdown sequence.
 *
 * Step 1: Close all WebSocket sessions
 * Step 2: Drain wsExecutor  (30s timeout)
 * Step 3: Drain aiExecutor  (30s timeout)
 * Step 4: Drain appExecutor (30s timeout)
 * Step 5: Force-cancel anything still running
 */
@Component
public class GracefulShutdownManager {

    private static final Logger log =
            LoggerFactory.getLogger(GracefulShutdownManager.class);

    private final SessionManager     sessionManager;
    private final ThreadPoolExecutor wsExecutor;
    private final ThreadPoolExecutor aiExecutor;
    private final ThreadPoolExecutor appExecutor;

    public GracefulShutdownManager(
            SessionManager sessionManager,
            @Qualifier("wsExecutor")  ThreadPoolExecutor wsExecutor,
            @Qualifier("aiExecutor")  ThreadPoolExecutor aiExecutor,
            @Qualifier("appExecutor") ThreadPoolExecutor appExecutor) {
        this.sessionManager = sessionManager;
        this.wsExecutor     = wsExecutor;
        this.aiExecutor     = aiExecutor;
        this.appExecutor    = appExecutor;
    }

    public void shutdown() {
        log.info("═══════════════════════════════════════");
        log.info("  GRACEFUL SHUTDOWN STARTED");
        log.info("═══════════════════════════════════════");

        log.info("Step 1: Closing {} active WebSocket sessions...",
                sessionManager.getActiveCount());
        closeAllSessions();

        log.info("Step 2: Draining wsExecutor (queue: {})...",
                wsExecutor.getQueue().size());
        drainExecutor("wsExecutor", wsExecutor, 30);

        log.info("Step 3: Draining aiExecutor (queue: {})...",
                aiExecutor.getQueue().size());
        drainExecutor("aiExecutor", aiExecutor, 30);

        log.info("Step 4: Draining appExecutor (queue: {})...",
                appExecutor.getQueue().size());
        drainExecutor("appExecutor", appExecutor, 30);

        log.info("═══════════════════════════════════════");
        log.info("  GRACEFUL SHUTDOWN COMPLETE ✅");
        log.info("═══════════════════════════════════════");
    }

    private void closeAllSessions() {
        for (WebSocketSession session : sessionManager.getAllSessions()) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseStatus(1001, "Server shutting down"));
                    log.debug("Closed session: {}", session.getId());
                }
            } catch (IOException e) {
                log.warn("Could not close session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private void drainExecutor(String name, ThreadPoolExecutor executor, int timeoutSeconds) {
        executor.shutdown();
        try {
            boolean finished = executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            if (finished) {
                log.info("{}: drained cleanly ✅ (completed: {})",
                        name, executor.getCompletedTaskCount());
            } else {
                log.warn("{}: timeout after {}s — force-cancelling {} running tasks",
                        name, timeoutSeconds, executor.getActiveCount());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("{}: interrupted during drain — force shutdown", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}