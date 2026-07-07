package com.cedrustech.hrsystem;

import com.cedrustech.hrsystem.shutdown.GracefulShutdownManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * CedrusTech Backend — Entry Point
 *
 * Architecture:
 *   Frontend (HTML/JS) ──► Java Backend (port 8081)
 *                               │
 *                 ┌─────────────┼─────────────┐
 *                 │             │             │
 *           WebSocket       REST API      Metrics
 *           /ws/chat      /chat /apply    /metrics
 *                 │             │
 *                 └──── ChatService ──── AIProxyService
 *                                              │
 *                                    Python RAG (port 8000)
 *                                              │
 *                                         SQL Server
 */
@SpringBootApplication
@EnableAsync
public class HrsystemApplication {

    private static final Logger log = LoggerFactory.getLogger(HrsystemApplication.class);

    public static void main(String[] args) {

        log.info("==============================================");
        log.info("  Hrsystem Backend Starting — Java 17        ");
        log.info("==============================================");

        ConfigurableApplicationContext ctx =
                SpringApplication.run(HrsystemApplication.class, args);

        // Register graceful shutdown hook
        GracefulShutdownManager shutdownManager =
                ctx.getBean(GracefulShutdownManager.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — draining tasks...");
            shutdownManager.shutdown();
        }, "shutdown-hook"));

        log.info("==============================================");
        log.info("  Server ready at http://localhost:8081       ");
        log.info("  WebSocket  at ws://localhost:8081/ws/chat   ");
        log.info("  Metrics    at http://localhost:8081/metrics  ");
        log.info("==============================================");
    }
}