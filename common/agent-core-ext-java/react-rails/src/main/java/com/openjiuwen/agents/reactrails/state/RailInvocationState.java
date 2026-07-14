/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.state;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Stores mutable rail state in the callback context shared by one agent invocation.
 *
 * @since 2026-07
 */
public final class RailInvocationState {
    private static final AtomicLong NEXT_KEY_ID = new AtomicLong();

    private RailInvocationState() {
    }

    /**
     * Creates a unique, namespaced key for one rail instance.
     *
     * @param owner rail implementation class
     * @return context-extra key
     */
    public static String newKey(Class<?> owner) {
        return Objects.requireNonNull(owner, "owner").getName() + ".invocationState." + NEXT_KEY_ID.incrementAndGet();
    }

    /**
     * Returns the current invocation's state, creating it on first access.
     *
     * @param context callback context
     * @param key unique rail-instance key
     * @param stateType expected state type
     * @param factory state factory
     * @param <T> state type
     * @return current invocation state
     */
    public static <T> T get(AgentCallbackContext context, String key, Class<T> stateType, Supplier<T> factory) {
        Map<String, Object> extra = Objects.requireNonNull(context, "context").getExtra();
        if (extra == null) {
            throw new IllegalStateException("AgentCallbackContext.extra must not be null");
        }
        Object value = extra.computeIfAbsent(key, ignored -> factory.get());
        if (!stateType.isInstance(value)) {
            throw new IllegalStateException("Unexpected invocation state type for key " + key);
        }
        return stateType.cast(value);
    }
}
