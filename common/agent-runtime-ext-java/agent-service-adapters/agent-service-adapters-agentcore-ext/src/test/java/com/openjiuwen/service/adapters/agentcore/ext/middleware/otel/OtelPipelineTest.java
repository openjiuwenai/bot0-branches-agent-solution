/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end pipeline test: factory-built provider + JSON handler + session holder bound,
 * asserting the assembled span tree carries every contract-critical attribute.
 */
class OtelPipelineTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    @AfterEach
    void clearHolder() {
        SessionContextHolder.setCurrentSession(null);
    }

    private Session sessionOf(String id) {
        return new Session() {
            @Override
            public String getSessionId() {
                return id;
            }

            @Override
            public Object getState(String key) {
                return null;
            }

            @Override
            public void updateState(Map<String, Object> state) {
            }
        };
    }

    @Test
    void fullPipeline_producesContractCompliantSpans() {
        List<SpanData> spans = producePipelineSpans();
        assertThat(spans).hasSize(2);
        SpanData llmSpan = spans.stream().filter(s -> s.getName().startsWith("llm.")).findFirst().orElseThrow();
        SpanData chainSpan = spans.stream().filter(s -> s.getName().startsWith("chain.")).findFirst().orElseThrow();

        // tree: llm hangs under chain, same trace
        assertThat(llmSpan.getParentSpanId()).isEqualTo(chainSpan.getSpanId());
        assertThat(llmSpan.getTraceId()).isEqualTo(chainSpan.getTraceId());
        assertContractAttributes(chainSpan, llmSpan);
    }

    private List<SpanData> producePipelineSpans() {
        OtelTracerConfig config = OtelTracerConfig.builder()
                .exporterType("otlp")
                .exporterEndpoint("http://localhost:4317")
                .serviceName("edp-agent")
                .isRedactionEnabled(false)
                .maxAttrLength(-1)
                .build();
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config, exporter, "inst-it");
        OtelJsonAgentHandler handler = new OtelJsonAgentHandler(provider.get("it"), config);

        SessionContextHolder.setCurrentSession(sessionOf("conv-it"));

        TraceAgentSpan chain = new TraceAgentSpan("trace-it", "inv-1", "");
        chain.setInvokeType("chain");
        chain.setName("EDPAgent");
        TraceAgentSpan llm = new TraceAgentSpan("trace-it", "inv-2", "inv-1");
        llm.setInvokeType("llm");
        llm.setName("model-x");

        handler.onChainStart(chain, Map.of("query", "推荐理财产品"), Map.of("class_name", "EDPAgent"));
        handler.onLlmStart(llm, List.of(Map.of("role", "user", "content", "推荐")), Map.of("class_name", "model-x"));
        handler.onLlmEnd(llm, Map.of("outputs", Map.of("role", "assistant", "content", "好的",
                "usage", Map.of("input_tokens", 5, "output_tokens", 7), "finish_reason", "stop")));
        handler.onChainEnd(chain, Map.of("answer", "好的"));
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        List<SpanData> spans = exporter.getFinishedSpanItems();
        provider.close();
        return spans;
    }

    private static void assertContractAttributes(SpanData chainSpan, SpanData llmSpan) {
        // session.id on every span
        assertThat(chainSpan.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("conv-it");
        assertThat(llmSpan.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("conv-it");

        // legal JSON content
        assertThat(chainSpan.getAttributes().get(AttributeKey.stringKey("openjiuwen.agent.inputs")))
                .isEqualTo("{\"query\":\"推荐理财产品\"}");

        // token dual-write
        assertThat(llmSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.prompt_tokens"))).isEqualTo(5L);
        assertThat(llmSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
        assertThat(llmSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(7L);

        // business finish_reason dual-write
        assertThat(llmSpan.getAttributes().get(AttributeKey.stringKey("openjiuwen.llm.finish_reason")))
                .isEqualTo("stop");

        // resource
        assertThat(chainSpan.getResource().getAttribute(AttributeKey.stringKey("service.instance.id")))
                .isEqualTo("inst-it");
    }
}
