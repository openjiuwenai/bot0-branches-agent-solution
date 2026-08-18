/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session stash carrying the agent-thread OTel context to the coordinator threads
 * that issue remote (A2A downstream) calls. The rail writes the chain-span context at
 * tool-call time (agent thread); the {@link OtelRemoteAgentCallerDecorator} reads it on
 * coordinator threads so dispatch/versatile spans join the same trace under chain.
 *
 * @since 2026-08-17
 */
public final class EgressContextStash {
    private static final Map<String, Context> BY_SESSION = new ConcurrentHashMap<>();

    private EgressContextStash() {
    }

    /**
     * Stashes the current OTel context for a conversation.
     *
     * @param conversationId conversation key
     * @param context        OTel context to stash
     */
    public static void put(String conversationId, Context context) {
        if (conversationId != null && context != null) {
            BY_SESSION.put(conversationId, context);
        }
    }

    /**
     * Finds the stashed context for a remote-context id. Multi-member batches combine the
     * id as {@code {conversationId}_{batchId}_{toolCallId}}, so fall back to a prefix
     * match against known conversation ids.
     *
     * @param contextId remote context id (bare or combined)
     * @return stashed context, or empty
     */
    public static Optional<Context> find(String contextId) {
        if (contextId == null) {
            return Optional.empty();
        }
        Context exact = BY_SESSION.get(contextId);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, Context> entry : BY_SESSION.entrySet()) {
            if (contextId.startsWith(entry.getKey() + "_")) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the conversation id whose entry matches the remote-context id (for
     * attribute building).
     *
     * @param contextId remote context id (bare or combined)
     * @return matched conversation id, or empty
     */
    public static Optional<String> findConversationId(String contextId) {
        if (contextId == null) {
            return Optional.empty();
        }
        if (BY_SESSION.containsKey(contextId)) {
            return Optional.of(contextId);
        }
        for (String key : BY_SESSION.keySet()) {
            if (contextId.startsWith(key + "_")) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * Drops the stashed context for a conversation.
     *
     * @param conversationId conversation key
     */
    public static void remove(String conversationId) {
        if (conversationId != null) {
            BY_SESSION.remove(conversationId);
        }
    }
}
