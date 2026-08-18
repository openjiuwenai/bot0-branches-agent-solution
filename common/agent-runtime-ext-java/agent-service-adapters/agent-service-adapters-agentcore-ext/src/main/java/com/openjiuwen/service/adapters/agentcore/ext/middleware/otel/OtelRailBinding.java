/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;

import io.opentelemetry.api.trace.Span;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-request binding of the session-filtered trajectory rail onto the agent instance.
 * Mirrors the ClientToolRail bind/unbind pattern: registration is synchronized on the
 * agent, DeepAgent is unwrapped to its inner agent, and a missing conversation id yields
 * a disabled no-op binding (trajectory stays off for that request).
 *
 * @since 2026-08-07
 */
public final class OtelRailBinding implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelRailBinding.class);

    private final Object lockAgent;
    private final BaseAgent railTarget;
    private final SessionFilteredAgentRail rail;
    private boolean closed;

    private OtelRailBinding(Object lockAgent, BaseAgent railTarget, SessionFilteredAgentRail rail) {
        this.lockAgent = lockAgent;
        this.railTarget = railTarget;
        this.rail = rail;
    }

    /**
     * Binds a trajectory rail for one request. Returns a disabled binding when the agent
     * or conversation id is absent, or when the agent type is unsupported.
     *
     * @param agent          agent instance or DeepAgent wrapper (may be null)
     * @param conversationId request conversation id (may be null/blank)
     * @param httpSpan       http root span for same-trace bridging (may be null)
     * @return the binding; always call {@link #close()} in a finally block
     */
    public static OtelRailBinding bind(Object agent, String conversationId, Span httpSpan) {
        if (agent == null || conversationId == null || conversationId.isBlank()) {
            if (conversationId == null || conversationId.isBlank()) {
                LOGGER.warn("otel trajectory disabled for request: missing conversationId");
            }
            return new OtelRailBinding(null, null, null);
        }
        if (agent instanceof DeepAgent deepAgent) {
            synchronized (deepAgent) {
                deepAgent.ensureInitialized();
                return installLocked(deepAgent.getAgent(), deepAgent, conversationId, httpSpan);
            }
        }
        if (agent instanceof BaseAgent baseAgent) {
            synchronized (baseAgent) {
                return installLocked(baseAgent, baseAgent, conversationId, httpSpan);
            }
        }
        LOGGER.warn("otel trajectory disabled: unsupported agent type {}", agent.getClass().getSimpleName());
        return new OtelRailBinding(null, null, null);
    }

    private static OtelRailBinding installLocked(BaseAgent target, Object lockAgent,
                                                 String conversationId, Span httpSpan) {
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail(conversationId, new OtelRail(), httpSpan);
        target.registerRail(rail);
        return new OtelRailBinding(lockAgent, target, rail);
    }

    public boolean isEnabled() {
        return rail != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (lockAgent == null || rail == null) {
            return;
        }
        synchronized (lockAgent) {
            try {
                railTarget.unregisterRail(rail);
            } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException e) {
                LOGGER.warn("otel rail unregister failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
