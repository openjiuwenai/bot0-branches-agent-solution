/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import io.opentelemetry.api.trace.Span;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-level carrier that hands the http root span from the entry filter (request
 * thread) to the per-request rail (loop thread), independent of thread boundaries.
 * Entries are keyed by conversation id; mutable fields are published via volatile.
 *
 * @since 2026-08-07
 */
public final class HttpContextBridge {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(String conversationId, Entry entry) {
        entries.put(conversationId, entry);
    }

    public Entry get(String conversationId) {
        return entries.get(conversationId);
    }

    public void remove(String conversationId) {
        entries.remove(conversationId);
    }

    /** Bridge entry: holds the http root span so the loop thread can bridge and write to it. */
    public static final class Entry {
        private final Span span;
        private volatile String engineTraceId;

        public Entry(Span span) {
            this.span = span;
        }

        public Span getSpan() {
            return span;
        }

        public String getEngineTraceId() {
            return engineTraceId;
        }

        public void setEngineTraceId(String engineTraceId) {
            this.engineTraceId = engineTraceId;
        }
    }
}
