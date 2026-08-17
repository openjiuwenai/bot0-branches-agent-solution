/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * OtelJsonAgentHandler 正文 JSON 化的单元测试。
 */
class OtelJsonAgentHandlerTest {

    private static final AttributeKey<String> AGENT_INPUTS = AttributeKey.stringKey("openjiuwen.agent.inputs");
    private static final AttributeKey<String> AGENT_OUTPUTS = AttributeKey.stringKey("openjiuwen.agent.outputs");

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private OtelJsonAgentHandler handler() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelTracerConfig config = OtelTracerConfig.builder()
                .exporterType("console")
                .isRedactionEnabled(false)
                .maxAttrLength(-1)
                .build();
        return new OtelJsonAgentHandler(provider.get("test"), config);
    }

    private TraceAgentSpan chainSpan(String invokeId) {
        TraceAgentSpan span = new TraceAgentSpan("trace-1", invokeId, "");
        span.setInvokeType("chain");
        span.setName("EDPAgent");
        return span;
    }

    private List<SpanData> finished() {
        return exporter.getFinishedSpanItems();
    }

    @Test
    void chainInputs_areSerializedToLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-1");
        handler.onChainStart(span, Map.of("query", "推荐理财产品"), null);
        handler.onChainEnd(span, Map.of("outputs", Map.of("answer", "您好")));
        SpanData data = finished().get(0);
        assertThat(data.getAttributes().get(AGENT_INPUTS)).isEqualTo("{\"query\":\"推荐理财产品\"}");
    }

    @Test
    void chainOutputs_areSerializedToLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-2");
        handler.onChainStart(span, Map.of("query", "q"), null);
        handler.onChainEnd(span, Map.of("answer", "您好"));
        SpanData data = finished().get(0);
        String outputs = data.getAttributes().get(AGENT_OUTPUTS);
        assertThat(outputs).contains("\"answer\":\"您好\"");
        assertThat(outputs).doesNotContain("=");
    }

    @Test
    void pluginInputs_areSerializedToLegalJson() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = new TraceAgentSpan("trace-1", "inv-3", "inv-1");
        span.setInvokeType("plugin");
        span.setName("read_file");
        handler.onPluginStart(span, Map.of("inputs", Map.of("command", "find /tmp")), null);
        handler.onPluginEnd(span, Map.of("code", 0));
        SpanData data = finished().get(0);
        assertThat(data.getAttributes().get(AGENT_INPUTS)).contains("\"command\":\"find /tmp\"");
    }

    @Test
    void nullInputs_leaveAttributeAbsent() {
        OtelJsonAgentHandler handler = handler();
        TraceAgentSpan span = chainSpan("inv-4");
        handler.onChainStart(span, null, null);
        handler.onChainEnd(span, null);
        SpanData data = finished().get(0);
        assertThat(data.getAttributes().get(AGENT_INPUTS)).isNull();
        assertThat(data.getAttributes().get(AGENT_OUTPUTS)).isNull();
    }
}
