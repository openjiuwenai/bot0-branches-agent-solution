/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import io.opentelemetry.api.trace.Span;

import java.util.Optional;

/**
 * Static facade letting host-side handlers (including modules that only optionally depend
 * on this one) bind the per-request trajectory rail without Spring wiring. Activated by
 * the auto-configuration when trajectory export is enabled.
 *
 * @since 2026-08-07
 */
public final class OtelRuntimeSupport {

    private static volatile HttpContextBridge bridge;
    private static volatile boolean enabled;

    private OtelRuntimeSupport() {
    }

    /** Called by the auto-configuration at startup. */
    public static void activate(HttpContextBridge httpContextBridge) {
        bridge = httpContextBridge;
        enabled = true;
    }

    /** For tests / shutdown: deactivate and drop the bridge. */
    public static void deactivate() {
        enabled = false;
        bridge = null;
    }

    /**
     * Returns whether trajectory export has been activated.
     *
     * @return true when activated
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the active http context bridge, if activated.
     *
     * @return bridge, or empty when not activated
     */
    public static Optional<HttpContextBridge> bridge() {
        return Optional.ofNullable(bridge);
    }

    /**
     * Binds the trajectory rail for one request when enabled; otherwise returns a disabled
     * no-op binding. Safe to call from optional-dependency hosts.
     *
     * @param agent          agent instance (or DeepAgent wrapper)
     * @param conversationId request conversation id
     * @return binding to close in a finally block
     */
    public static OtelRailBinding bindRequest(Object agent, String conversationId) {
        if (!enabled || bridge == null) {
            return OtelRailBinding.bind(null, null, null);
        }
        Span httpSpan = null;
        if (conversationId != null) {
            HttpContextBridge.Entry entry = bridge.get(conversationId);
            httpSpan = entry != null ? entry.getSpan() : null;
        }
        return OtelRailBinding.bind(agent, conversationId, httpSpan);
    }
}
