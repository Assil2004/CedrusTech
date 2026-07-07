package com.cedrustech.hrsystem.controller;

import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.ApiResponse;
import com.cedrustech.hrsystem.model.ChatMessage;
import com.cedrustech.hrsystem.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.concurrent.*;

/**
 * REST /chat endpoint — unified with ApiResponse.
 *
 * Frontend JS expects:
 *   POST http://localhost:8081/chat
 *   Body: { "message": "..." }
 *   Reply: { "success": true, "data": { "reply": "..." } }
 *
 * The frontend script.js reads:
 *   data?.data?.reply || data?.reply
 * so ApiResponse wrapper is fully compatible.
 */
@RestController
@CrossOrigin(origins = "*")
public class ChatRestController {

    private static final Logger log =
            LoggerFactory.getLogger(ChatRestController.class);

    private final ChatService      chatService;
    private final MetricsCollector metrics;
    private final ExecutorService  wsExecutor;

    public ChatRestController(
        ChatService chatService,
        MetricsCollector metrics,
        @Qualifier("wsExecutor") ThreadPoolExecutor wsExecutor
    ) {
        this.chatService = chatService;
        this.metrics     = metrics;
        this.wsExecutor  = wsExecutor;
    }

    // ─── Root health check ───────────────────────────
    @GetMapping("/")
    public ApiResponse<Void> home() {
        return ApiResponse.ok("CedrusTech AI API Running ✅ (Java 17)");
    }

    // ─────────────────────────────────────────────────
    // POST /chat — async DeferredResult
    // ─────────────────────────────────────────────────
    @PostMapping("/chat")
    public DeferredResult<ResponseEntity<ApiResponse<?>>> chat(
        @RequestBody Map<String, String> body
    ) {
        DeferredResult<ResponseEntity<ApiResponse<?>>> result =
            new DeferredResult<>(32_000L);

        String userMessage = body.get("message");
        if (userMessage == null || userMessage.isBlank()) {
            result.setResult(ResponseEntity.badRequest()
                .body(ApiResponse.error("Field 'message' is required.")));
            return result;
        }

        // FIX: use correct ChatMessage factory from com.cedrustech.hrsystem.model
        ChatMessage msg = ChatMessage.ofUser("rest-client", userMessage);
        metrics.messageReceived();
        long startMs = System.currentTimeMillis();

        CompletableFuture
            .supplyAsync(() -> chatService.processMessage(msg), wsExecutor)
            .orTimeout(30, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                log.error("AI error: {}", ex.getMessage());
                metrics.requestFailed();
                return "I'm temporarily unavailable. Please try again.";
            })
            .thenAccept(reply -> {
                metrics.messageDelivered();
                metrics.recordLatency(System.currentTimeMillis() - startMs);
                result.setResult(ResponseEntity.ok(
                    ApiResponse.ok("Chat reply generated",
                        Map.of("reply", reply))
                ));
            });

        result.onTimeout(() -> {
            metrics.requestFailed();
            result.setErrorResult(ResponseEntity.status(503)
                .body(ApiResponse.error("Request timed out. Please try again.")));
        });

        return result;
    }
}