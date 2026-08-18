/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.session.tracer.TracerHandlerRegistry;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import io.opentelemetry.api.trace.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the JSON-producing agent handler into the engine's global handler registry.
 * Registration is defensive: an existing same-name handler is unregistered first (the
 * registry rejects duplicates), and any failure is logged without breaking startup.
 *
 * @since 2026-08-07
 */
public final class OtelAgentRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelAgentRegistrar.class);
    private static final String HANDLER_NAME = "otel_agent";

    private final Tracer tracer;
    private final OtelTracerConfig config;

    public OtelAgentRegistrar(Tracer tracer, OtelTracerConfig config) {
        this.tracer = tracer;
        this.config = config;
    }

    /**
     * Registers (or re-registers) the JSON handler; failures are logged, never thrown.
     */
    public void register() {
        try {
            if (TracerHandlerRegistry.getAgentHandlers().containsKey(HANDLER_NAME)) {
                TracerHandlerRegistry.unregisterHandler(HANDLER_NAME);
            }
            TracerHandlerRegistry.registerHandler(HANDLER_NAME, new OtelJsonAgentHandler(tracer, config));
        } catch (IllegalStateException | IllegalArgumentException e) {
            LOGGER.warn("otel agent handler registration failed: {}", e.getClass().getSimpleName());
        }
    }
}
