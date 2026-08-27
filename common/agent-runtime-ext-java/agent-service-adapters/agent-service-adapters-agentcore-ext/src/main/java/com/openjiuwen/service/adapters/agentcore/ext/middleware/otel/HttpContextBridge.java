/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import io.opentelemetry.api.trace.Span;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Stores the bridge entry for a conversation.
     *
     * @param conversationId conversation key
     * @param entry          bridge entry holding the http root span
     */
    public void put(String conversationId, Entry entry) {
        entries.put(conversationId, entry);
    }

    /**
     * Looks up the bridge entry for a conversation.
     *
     * @param conversationId conversation key
     * @return bridge entry, or null when absent
     */
    public Entry get(String conversationId) {
        return entries.get(conversationId);
    }

    /**
     * Finds the bridge entry for a (possibly combined) context id: exact match first,
     * then longest-prefix match (batch member contextIds append suffixes to the
     * conversation id).
     *
     * @param contextId bare or combined context id
     * @return matched key and entry, or empty
     */
    public Optional<Match> find(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return Optional.empty();
        }
        Entry exact = entries.get(contextId);
        if (exact != null) {
            return Optional.of(new Match(contextId, exact));
        }
        return entries.entrySet().stream()
                .filter(e -> contextId.startsWith(e.getKey() + "_"))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(e -> new Match(e.getKey(), e.getValue()));
    }

    /**
     * One prefix-matched bridge entry.
     *
     * @param conversationId the canonical conversation key
     * @param entry          the bridge entry
     */
    public record Match(String conversationId, Entry entry) {
    }

    /**
     * Drops the bridge entry for a conversation.
     *
     * @param conversationId conversation key
     */
    public void remove(String conversationId) {
        entries.remove(conversationId);
    }

    /**
     * Bridge entry: holds the http root span so the loop thread can bridge and write to it.
     */
    public static final class Entry {
        private final Span span;
        private volatile String engineTraceId;

        /**
         * Creates an entry.
         *
         * @param span http root span
         */
        public Entry(Span span) {
            this.span = span;
        }

        /**
         * Returns the http root span.
         *
         * @return root span
         */
        public Span getSpan() {
            return span;
        }

        /**
         * Returns the engine-side trace id written back by the rail.
         *
         * @return engine trace id, or null when not yet written
         */
        public String getEngineTraceId() {
            return engineTraceId;
        }

        /**
         * Records the engine-side trace id.
         *
         * @param engineTraceId engine trace id
         */
        public void setEngineTraceId(String engineTraceId) {
            this.engineTraceId = engineTraceId;
        }
    }
}
