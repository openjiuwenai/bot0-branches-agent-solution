/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.transport.spi.RawResponseEvent;
import com.openjiuwen.client.transport.spi.RawResponseObserver;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes observation callbacks away from HTTP/SSE reader threads.
 * The queue is bounded and drops the newest event when full; cumulative loss is exposed
 * through droppedCount() and marked on the next accepted event.
 */
final class RawResponseDispatcher implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RawResponseDispatcher.class.getName());
    private final RawResponseObserver observer;
    private final Executor executor;
    private final int capacity;
    private final ArrayDeque<RawResponseEvent> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;
    private long dropped;
    private long pendingDropped;

    RawResponseDispatcher(RawResponseObserver observer, Executor executor, int capacity) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (capacity < 1) {
            throw new IllegalArgumentException("raw response queue capacity must be positive");
        }
        this.capacity = capacity;
    }

    void offer(RawResponseEvent event) {
        boolean schedule = false;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (queue.size() >= capacity) {
                dropped++;
                pendingDropped++;
                return;
            }
            long droppedBefore = pendingDropped;
            pendingDropped = 0L;
            queue.addLast(droppedBefore == 0L ? event : event.withDroppedBefore(droppedBefore));
            if (!draining) {
                draining = true;
                schedule = true;
            }
        }
        if (schedule) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException ex) {
                synchronized (this) {
                    draining = false;
                    long rejected = queue.size();
                    dropped += rejected;
                    pendingDropped += rejected;
                    queue.clear();
                    notifyAll();
                }
                LOG.log(Level.FINE, "raw response observer executor rejected work", ex);
            }
        }
    }

    synchronized long droppedCount() {
        return dropped;
    }

    private void drain() {
        for (;;) {
            RawResponseEvent event;
            synchronized (this) {
                event = queue.pollFirst();
                if (event == null) {
                    draining = false;
                    notifyAll();
                    return;
                }
            }
            try {
                observer.onResponse(event);
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "raw response observer failed", ex);
            }
        }
    }

    @Override
    public void close() {
        close(Duration.ZERO);
    }

    void close(Duration timeout) {
        long nanos = Math.max(0L, timeout.toNanos());
        synchronized (this) {
            closed = true;
            long deadline = System.nanoTime() + nanos;
            while ((draining || !queue.isEmpty()) && nanos > 0L) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, nanos);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                nanos = deadline - System.nanoTime();
            }
            if (!queue.isEmpty()) {
                long pending = queue.size();
                dropped += pending;
                pendingDropped += pending;
                queue.clear();
            }
            notifyAll();
        }
    }
}
