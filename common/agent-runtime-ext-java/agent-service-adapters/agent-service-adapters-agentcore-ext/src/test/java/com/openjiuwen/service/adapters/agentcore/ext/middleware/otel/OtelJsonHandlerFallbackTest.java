/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Regression for DFX-001-P2: toJson fallback must not emit non-JSON attribute values,
 * and serialization failure must never propagate to the agent execution path.
 */
class OtelJsonHandlerFallbackTest {

    private static final AttributeKey<String> AGENT_INPUTS = AttributeKey.stringKey("openjiuwen.agent.inputs");
    private static final AttributeKey<String> AGENT_OUTPUTS = AttributeKey.stringKey("openjiuwen.agent.outputs");

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    /** A bean whose getter always throws — Jackson cannot serialize it. */
    public static final class FailingBean {
        /**
         * Always-failing getter.
         *
         * @return never returns
         */
        public String getValue() {
            throw new IllegalStateException("intentional serialization failure");
        }
    }

    private OtelJsonAgentHandler handler() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelTracerConfig config = OtelTracerConfig.builder()
                .exporterType("console").isRedactionEnabled(false).maxAttrLength(-1).build();
        return new OtelJsonAgentHandler(provider.get("test"), config);
    }

    private TraceAgentSpan chainSpan(String invokeId) {
        TraceAgentSpan span = new TraceAgentSpan("trace-1", invokeId, "");
        span.setInvokeType("chain");
        span.setName("EDPAgent");
        return span;
    }

    private static boolean isLegalJson(String value) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    @Test
    void unserializableChainInput_attributeOmittedOrLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-f1");
        handler.onChainStart(span, Map.of("bad", new FailingBean()), null);
        handler.onChainEnd(span, null);
        SpanData data = exporter.getFinishedSpanItems().get(0);
        String value = data.getAttributes().get(AGENT_INPUTS);
        assertThat(value == null || isLegalJson(value))
                .as("attribute must be absent or legal JSON, got: %s", value)
                .isTrue();
    }

    @Test
    void unserializableChainOutput_attributeOmittedOrLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-f2");
        handler.onChainStart(span, Map.of("query", "q"), null);
        handler.onChainEnd(span, Map.of("bad", new FailingBean()));
        SpanData data = exporter.getFinishedSpanItems().get(0);
        String value = data.getAttributes().get(AGENT_OUTPUTS);
        assertThat(value == null || isLegalJson(value)).isTrue();
    }

    @Test
    void unserializablePluginInput_attributeOmittedOrLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = new TraceAgentSpan("trace-1", "inv-f3", "inv-f2");
        span.setInvokeType("plugin");
        span.setName("read_file");
        handler.onPluginStart(span, Map.of("bad", new FailingBean()), null);
        handler.onPluginEnd(span, null);
        SpanData data = exporter.getFinishedSpanItems().get(0);
        String value = data.getAttributes().get(AGENT_INPUTS);
        assertThat(value == null || isLegalJson(value)).isTrue();
    }

    @Test
    void unserializablePluginOutput_attributeOmittedOrLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = new TraceAgentSpan("trace-1", "inv-f4", "inv-f2");
        span.setInvokeType("plugin");
        span.setName("read_file");
        handler.onPluginStart(span, Map.of("inputs", Map.of("a", 1)), null);
        handler.onPluginEnd(span, Map.of("bad", new FailingBean()));
        SpanData data = exporter.getFinishedSpanItems().get(0);
        String value = data.getAttributes().get(AGENT_OUTPUTS);
        assertThat(value == null || isLegalJson(value)).isTrue();
    }

    @Test
    void selfReferencingMap_doesNotCrash() {
        OtelJsonAgentHandler handler = handler();
        Map<String, Object> self = new java.util.HashMap<>();
        self.put("self", self);
        TraceAgentSpan span = chainSpan("inv-f5");
        assertThatCode(() -> {
            handler.onChainStart(span, self, null);
            handler.onChainEnd(span, null);
        }).doesNotThrowAnyException();
        SpanData data = exporter.getFinishedSpanItems().get(0);
        String value = data.getAttributes().get(AGENT_INPUTS);
        assertThat(value == null || isLegalJson(value)).isTrue();
    }

    @Test
    void normalInputs_stillSerializedAsJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-f6");
        handler.onChainStart(span, Map.of("query", "理财产品"), null);
        handler.onChainEnd(span, Map.of("answer", "ok"));
        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AGENT_INPUTS)).isEqualTo("{\"query\":\"理财产品\"}");
        assertThat(data.getAttributes().get(AGENT_OUTPUTS)).isEqualTo("{\"answer\":\"ok\"}");
    }

    @Test
    void nullAndStringInputs_unchanged() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-f7");
        handler.onChainStart(span, null, null);
        handler.onChainEnd(span, null);
        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AGENT_INPUTS)).isNull();
        assertThat(data.getAttributes().get(AGENT_OUTPUTS)).isNull();
    }
}
