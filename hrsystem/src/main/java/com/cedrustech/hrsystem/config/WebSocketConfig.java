package com.cedrustech.hrsystem.config;

import com.cedrustech.hrsystem.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Concurrent Entry Point: WebSocket
 *
 * Registers /ws/chat as the real-time WebSocket endpoint.
 * Each client that connects gets its own WebSocketSession;
 * the Tomcat thread pool handles handshakes concurrently.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(chatWebSocketHandler, "/ws/chat")
            .setAllowedOrigins("*");
    }
}