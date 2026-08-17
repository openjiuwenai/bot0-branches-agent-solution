/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.extensions.tracerotel.OtelAgentHandler;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * OtelAgentHandler subclass that pre-serializes chain/tool inputs and outputs to JSON
 * strings before delegating. The base handler renders non-String payloads via
 * {@code String.valueOf} (Map.toString), which is not parseable JSON; the data contract
 * requires native JSON for {@code openjiuwen.agent.inputs/outputs}.
 *
 * @since 2026-08-07
 */
public class OtelJsonAgentHandler extends OtelAgentHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelJsonAgentHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Creates the JSON-serializing handler.
     *
     * @param tracer core tracer
     * @param config tracer configuration
     */
    public OtelJsonAgentHandler(Tracer tracer, OtelTracerConfig config) {
        super(tracer, config);
    }

    @Override
    public void onChainStart(TraceAgentSpan span, Object inputs, Map<String, Object> instanceInfo) {
        super.onChainStart(span, toJson(inputs).orElse(null), instanceInfo);
    }

    @Override
    public void onChainEnd(TraceAgentSpan span, Object outputs) {
        super.onChainEnd(span, toJson(outputs).orElse(null));
    }

    @Override
    public void onPluginStart(TraceAgentSpan span, Object inputs, Map<String, Object> instanceInfo) {
        super.onPluginStart(span, toJson(inputs).orElse(null), instanceInfo);
    }

    @Override
    public void onPluginEnd(TraceAgentSpan span, Object outputs) {
        super.onPluginEnd(span, toJson(outputs).orElse(null));
    }

    private static Optional<Object> toJson(Object value) {
        if (value == null || value instanceof String) {
            return Optional.ofNullable(value);
        }
        try {
            return Optional.of(MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException | IllegalStateException | IllegalArgumentException | StackOverflowError e) {
            // Fault isolation: omit the attribute rather than emitting non-JSON (or
            // propagating StackOverflowError from cyclic objects into the agent thread).
            LOGGER.warn("otel json handler: serialize failed, attribute omitted: {}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
