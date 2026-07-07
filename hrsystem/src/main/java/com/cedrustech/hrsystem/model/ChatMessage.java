package com.cedrustech.hrsystem.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChatMessage(
        String sessionId,
        String role,
        String content,
        String requestId,
        Instant timestamp
) {

    public ChatMessage {
        Objects.requireNonNull(sessionId,  "sessionId must not be null");
        Objects.requireNonNull(role,       "role must not be null");
        Objects.requireNonNull(content,    "content must not be null");
        Objects.requireNonNull(requestId,  "requestId must not be null");
        Objects.requireNonNull(timestamp,  "timestamp must not be null");

        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    public static ChatMessage ofUser(String sessionId, String content) {
        return new ChatMessage(
                sessionId,
                "user",
                content,
                UUID.randomUUID().toString(),
                Instant.now()
        );
    }

    public static ChatMessage ofAssistant(String sessionId, String content) {
        return new ChatMessage(
                sessionId,
                "assistant",
                content,
                UUID.randomUUID().toString(),
                Instant.now()
        );
    }

    public long ageMillis() {
        return Instant.now().toEpochMilli() - timestamp.toEpochMilli();
    }
}