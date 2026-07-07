package com.cedrustech.hrsystem.queue;

import com.cedrustech.hrsystem.metrics.MetricsCollector;
import com.cedrustech.hrsystem.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Per-session bounded message queue.
 *
 * Uses LinkedBlockingQueue with a fixed capacity.
 * When full, offer() returns false and drops the message
 * (backpressure) rather than blocking the caller.
 */
public class BoundedMessageQueue {

    private static final Logger log =
            LoggerFactory.getLogger(BoundedMessageQueue.class);

    private static final int DEFAULT_CAPACITY = 20;

    private final LinkedBlockingQueue<ChatMessage> queue;
    private final MetricsCollector metrics;
    private final String sessionId;

    public BoundedMessageQueue(
            String sessionId,
            MetricsCollector metrics,
            int capacity) {
        this.sessionId = sessionId;
        this.metrics   = metrics;
        this.queue     = new LinkedBlockingQueue<>(capacity);
    }

    public BoundedMessageQueue(
            String sessionId,
            MetricsCollector metrics) {
        this(sessionId, metrics, DEFAULT_CAPACITY);
    }

    /**
     * Non-blocking insert.
     * Returns false when queue is full — message is dropped.
     */
    public boolean offer(ChatMessage message) {
        boolean accepted = queue.offer(message);
        if (!accepted) {
            log.warn("Session [{}] queue full — dropping message", sessionId);
            metrics.messageDropped();
        }
        updateMetrics();
        return accepted;
    }

    /**
     * Poll with timeout.
     */
    public ChatMessage poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        ChatMessage msg = queue.poll(timeout, unit);
        updateMetrics();
        return msg;
    }

    /**
     * Blocking retrieval.
     */
    public ChatMessage take() throws InterruptedException {
        ChatMessage msg = queue.take();
        updateMetrics();
        return msg;
    }

    public int  size()              { return queue.size(); }
    public boolean isEmpty()        { return queue.isEmpty(); }
    public int  remainingCapacity() { return queue.remainingCapacity(); }

    private void updateMetrics() {
        metrics.updateChatQueueDepth(queue.size());
    }
}