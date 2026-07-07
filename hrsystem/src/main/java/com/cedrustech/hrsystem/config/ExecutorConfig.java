package com.cedrustech.hrsystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * ═══════════════════════════════════════════════════
 * ExecutorService, Bounded Queues, Backpressure
 * ═══════════════════════════════════════════════════
 *
 * Thread pool sizing rationale:
 * ┌─────────────────────────────────────────────────┐
 * │ wsExecutor (WebSocket / Chat processing)        │
 * │   Core  =  10  → handles steady 10 clients     │
 * │   Max   =  50  → burst: handles 50+ clients    │
 * │   Queue = 100  → bounded → triggers backpressure│
 * │   Keep-alive: 60s → scale down after idle       │
 * ├─────────────────────────────────────────────────┤
 * │ aiExecutor (AI proxy calls to Python)           │
 * │   Core  =  3   → Python GIL limits concurrency  │
 * │   Max   =  5   → semaphore also enforces this   │
 * │   Queue =  20  → AI calls are slow, queue small │
 * ├─────────────────────────────────────────────────┤
 * │ appExecutor (Job application processing)        │
 * │   Core  =  5   → DB writes + file saves         │
 * │   Max   = 20   → moderate burst                 │
 * │   Queue =  50  → bounded                        │
 * └─────────────────────────────────────────────────┘
 *
 * When queue is full → RejectedExecutionException
 * → caught and returned as HTTP 503 / WS error
 */
@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    @Value("${executor.core-pool-size:10}")
    private int corePoolSize;

    @Value("${executor.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${executor.queue-capacity:100}")
    private int queueCapacity;

    @Value("${executor.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    // ─────────────────────────────────────────────
    // WebSocket / Chat Executor
    // ─────────────────────────────────────────────
    @Bean(name = "wsExecutor")
    public ThreadPoolExecutor wsExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveSeconds, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new NamedThreadFactory("ws-worker"),
            new BackpressureRejectionHandler("ws")
        );
        executor.allowCoreThreadTimeOut(false);
        log.info("wsExecutor created: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    // ─────────────────────────────────────────────
    // AI Proxy Executor (limited — Python GIL)
    // ─────────────────────────────────────────────
    @Bean(name = "aiExecutor")
    public ThreadPoolExecutor aiExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            3, 5,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            new NamedThreadFactory("ai-worker"),
            new BackpressureRejectionHandler("ai")
        );
        log.info("aiExecutor created: core=3, max=5, queue=20 (Python GIL limited)");
        return executor;
    }

    // ─────────────────────────────────────────────
    // Application Processing Executor
    // ─────────────────────────────────────────────
    @Bean(name = "appExecutor")
    public ThreadPoolExecutor appExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            5, 20,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new NamedThreadFactory("app-worker"),
            new BackpressureRejectionHandler("app")
        );
        log.info("appExecutor created: core=5, max=20, queue=50");
        return executor;
    }

    // ─────────────────────────────────────────────
    // Named Thread Factory
    // ─────────────────────────────────────────────
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private int counter = 0;

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + (++counter));
            t.setDaemon(false);
            return t;
        }
    }

    // ─────────────────────────────────────────────
    // Backpressure rejection handler
    // When bounded queue is full → throws so callers
    // can return HTTP 503 / WebSocket error.
    // ─────────────────────────────────────────────
    public static class BackpressureRejectionHandler
            implements RejectedExecutionHandler {

        private static final Logger log =
                LoggerFactory.getLogger(BackpressureRejectionHandler.class);
        private final String poolName;

        BackpressureRejectionHandler(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("BACKPRESSURE: [{}] queue full (size={}) — task rejected",
                poolName, executor.getQueue().size());
            throw new RejectedExecutionException(
                "Server overloaded — [" + poolName + "] queue full");
        }
    }
}