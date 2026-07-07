package com.cedrustech.hrsystem.service;

import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AIProxyService — bridges Java backend → Python FastAPI (RAG + Ollama)
 *
 * Flow:
 *   ChatService.processMessage()
 *       └─► AIProxyService.ask()
 *               └─► POST http://localhost:8000/chat   (with retry)
 *                       └─► Python chatbot.py → Ollama llama3
 *
 * Week 7  : timeout + fallback on every call
 * Week 9  : Semaphore limits concurrent Python calls (Python GIL)
 * Week 10-14: correlationId flows Java → Python for tracing;
 *             retry with exponential backoff on transient failures
 */
@Service
public class AIProxyService implements InitializingBean {

    private static final Logger log =
            LoggerFactory.getLogger(AIProxyService.class);

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${ai.service.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${ai.service.max-concurrent-calls:5}")
    private int maxConcurrentCalls;

    @Value("${ai.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${ai.circuit-breaker.reset-timeout-ms:10000}")
    private long resetTimeoutMs;

    // Weeks 10-14: retry config
    @Value("${ai.retry.max-attempts:2}")
    private int maxRetryAttempts;

    @Value("${ai.retry.backoff-ms:500}")
    private long retryBackoffMs;

    private final MetricsCollector metrics;
    private final ObjectMapper     mapper = new ObjectMapper();

    private Semaphore aiSemaphore;

    // Circuit breaker state — AtomicInteger/AtomicLong for Week 4 visibility
    private final AtomicInteger failureCount    = new AtomicInteger(0);
    private final AtomicLong    lastFailureTime = new AtomicLong(0);

    // HTTP/1.1 forced — uvicorn does not support HTTP/2 upgrade on plain HTTP
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public AIProxyService(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void afterPropertiesSet() {
        aiSemaphore = new Semaphore(maxConcurrentCalls, true);

        log.info("══════════════════════════════════════════════");
        log.info("  AIProxyService initialized");
        log.info("  Python URL     : {}", aiServiceUrl);
        log.info("  Timeout        : {}s", timeoutSeconds);
        log.info("  Max concurrent : {} (Python GIL guard)", maxConcurrentCalls);
        log.info("  Retry          : max={} backoff={}ms", maxRetryAttempts, retryBackoffMs);
        log.info("  Circuit breaker: threshold={} resetMs={}", failureThreshold, resetTimeoutMs);
        log.info("══════════════════════════════════════════════");

        pingPython();
    }

    // ─────────────────────────────────────────────────
    // Public entry point — called by ChatService
    // Weeks 10-14: correlationId added for tracing
    // ─────────────────────────────────────────────────
    public String ask(String message, String sessionId, String correlationId) {

        log.info("▶ AI request — correlationId={} session={} message=\"{}\"",
                correlationId, sessionId,
                message.length() > 80 ? message.substring(0, 80) + "…" : message);

        if (isCircuitOpen()) {
            log.warn("⚡ Circuit breaker OPEN — rejecting (failures={})", failureCount.get());
            metrics.requestRejected();
            return fallbackResponse();
        }

        boolean acquired = false;

        try {
            acquired = aiSemaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("⏱ Semaphore timeout — all {} AI slots busy", maxConcurrentCalls);
                metrics.requestRejected();
                return fallbackResponse();
            }

            metrics.updateAiQueueDepth(maxConcurrentCalls - aiSemaphore.availablePermits());

            // Weeks 10-14: retry loop with exponential backoff
            return attemptWithRetry(message, sessionId, correlationId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("✗ AI request interrupted — session={}", sessionId);
            recordFailure();
            return fallbackResponse();

        } finally {
            if (acquired) aiSemaphore.release();
            metrics.updateAiQueueDepth(maxConcurrentCalls - aiSemaphore.availablePermits());
        }
    }

    // ─────────────────────────────────────────────────
    // Weeks 10-14: Retry with exponential backoff
    //
    // Attempt 1: immediate
    // Attempt 2: wait 500ms, then retry
    // After all attempts fail → circuit breaker + fallback
    //
    // Only retries on transient errors (connect/timeout).
    // Does NOT retry on 4xx (client errors like 422).
    // ─────────────────────────────────────────────────
    private String attemptWithRetry(
            String message, String sessionId, String correlationId)
            throws InterruptedException {

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {

            if (attempt > 1) {
                long backoff = retryBackoffMs * (attempt - 1); // 500ms, 1000ms...
                log.info("↻ AI retry attempt {}/{} — waiting {}ms | correlationId={}",
                        attempt, maxRetryAttempts, backoff, correlationId);
                Thread.sleep(backoff);
            }

            try {
                String result = doHttpCall(message, sessionId, correlationId, attempt);
                if (result != null) return result;

            } catch (java.net.ConnectException e) {
                log.warn("✗ Attempt {}/{} — connect failed: {}", attempt, maxRetryAttempts, e.getMessage());
                lastException = e;

            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("✗ Attempt {}/{} — timeout after {}s", attempt, maxRetryAttempts, timeoutSeconds);
                lastException = e;

            } catch (Exception e) {
                log.error("✗ Attempt {}/{} — unexpected error: {}", attempt, maxRetryAttempts, e.getMessage());
                lastException = e;
                break; // non-transient → don't retry
            }
        }

        // All attempts failed
        if (lastException instanceof java.net.ConnectException) {
            recordFailure();
            return "The AI assistant is currently offline. Please make sure the Python server is running.";
        }
        if (lastException instanceof java.net.http.HttpTimeoutException) {
            recordFailure();
            return "The AI is taking too long to respond. Please try again.";
        }

        recordFailure();
        return fallbackResponse();
    }

    // ─────────────────────────────────────────────────
    // Single HTTP call to Python
    // Returns reply string on success, null on non-200
    // ─────────────────────────────────────────────────
    private String doHttpCall(
            String message, String sessionId,
            String correlationId, int attempt) throws Exception {

        // Weeks 10-14: correlationId included so Python can log the full trace
        String bodyJson = mapper.writeValueAsString(Map.of(
                "message",        message,
                "session_id",     sessionId == null ? "default" : sessionId,
                "correlation_id", correlationId == null ? "none" : correlationId
        ));

        log.debug("→ POST {}/chat attempt={} body={}", aiServiceUrl, attempt, bodyJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceUrl + "/chat"))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.debug("← Python response: status={} body={}",
                response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            log.error("✗ Python returned HTTP {} — body: {}",
                    response.statusCode(), response.body());
            recordFailure();
            return null;
        }

        Map<?, ?> parsed = mapper.readValue(response.body(), Map.class);
        String reply = (String) parsed.get("reply");

        log.info("✓ AI reply — correlationId={} reply=\"{}\"",
                correlationId,
                reply != null && reply.length() > 80 ? reply.substring(0, 80) + "…" : reply);

        recordSuccess();

        return StringUtils.hasText(reply)
                ? reply
                : "I could not find that information in the company knowledge base.";
    }

    // ─────────────────────────────────────────────────
    // Startup health ping
    // ─────────────────────────────────────────────────
    private void pingPython() {
        try {
            HttpRequest ping = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp =
                    httpClient.send(ping, HttpResponse.BodyHandlers.ofString());
            log.info("✅ Python health check OK — status={} body={}",
                    resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("⚠ Python health check FAILED at {} — {}", aiServiceUrl, e.getMessage());
            log.warn("  ► Start Python: uvicorn api:app --host 0.0.0.0 --port 8000 --reload");
        }
    }

    // ─────────────────────────────────────────────────
    // Circuit breaker
    // ─────────────────────────────────────────────────
    private boolean isCircuitOpen() {
        if (failureCount.get() < failureThreshold) return false;
        long elapsed = System.currentTimeMillis() - lastFailureTime.get();
        if (elapsed < resetTimeoutMs) {
            log.warn("⚡ Circuit OPEN — {}ms until reset", resetTimeoutMs - elapsed);
            return true;
        }
        log.info("⚡ Circuit HALF-OPEN — resetting failure count");
        failureCount.set(0);
        return false;
    }

    private void recordFailure() {
        int count = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        metrics.requestFailed();
        log.warn("✗ AI failure #{} (threshold={})", count, failureThreshold);
    }

    private void recordSuccess() {
        if (failureCount.get() > 0) {
            log.info("✓ AI success — resetting circuit breaker");
            failureCount.set(0);
        }
    }

    private String fallbackResponse() {
        return "I'm temporarily unavailable. Please try again in a moment.";
    }
}