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
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract conformance test: assembles the full span tree of the data contract
 * (http.request root → chain → llm + tool) and asserts the required attribute matrix
 * per span type.
 */
class OtelContractTreeTest {

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

    private static AttributeKey<String> str(String key) {
        return AttributeKey.stringKey(key);
    }

    @Test
    void contractTree_allSpansCarryRequiredAttributes() {
        OtelTracerConfig config = OtelTracerConfig.builder()
                .exporterType("otlp").exporterEndpoint("http://localhost:4317")
                .serviceName("edp-agent").isRedactionEnabled(false).maxAttrLength(-1).build();
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config, exporter, "inst-ct");
        OtelJsonAgentHandler handler = new OtelJsonAgentHandler(provider.get("ct"), config);

        SessionContextHolder.setCurrentSession(sessionOf("conv-ct"));

        // http root (produced by the entry filter in production; here via the same provider,
        // with the session already bound so the processor injects session.id)
        io.opentelemetry.api.trace.Span httpRoot = provider.get("ct-http")
                .spanBuilder("http.request").setSpanKind(SpanKind.SERVER).startSpan();

        TraceAgentSpan chain = new TraceAgentSpan("trace-ct", "inv-1", "");
        chain.setInvokeType("chain");
        chain.setName("EDPAgent");
        chain.setChildInvokesId(List.of("inv-2", "inv-3"));
        TraceAgentSpan llm = new TraceAgentSpan("trace-ct", "inv-2", "inv-1");
        llm.setInvokeType("llm");
        llm.setName("model-x");
        TraceAgentSpan tool = new TraceAgentSpan("trace-ct", "inv-3", "inv-1");
        tool.setInvokeType("plugin");
        tool.setName("read_file");

        // bridge: chain root joins the http root trace (same mechanism as the delegate rail)
        io.opentelemetry.context.Scope scope = httpRoot.storeInContext(io.opentelemetry.context.Context.root())
                .makeCurrent();
        handler.onChainStart(chain, Map.of("query", "推荐理财产品"), Map.of("class_name", "EDPAgent"));
        handler.onLlmStart(llm, List.of(Map.of("role", "user", "content", "推荐")), Map.of("class_name", "model-x"));
        handler.onLlmEnd(llm, Map.of("outputs", Map.of("role", "assistant", "content", "好的",
                "usage", Map.of("input_tokens", 5, "output_tokens", 7), "finish_reason", "stop")));
        handler.onPluginStart(tool, Map.of("inputs", Map.of("path", "/tmp/a.txt")), Map.of("class_name", "read_file"));
        handler.onPluginEnd(tool, Map.of("code", 0));
        handler.onChainEnd(chain, Map.of("answer", "好的"));
        scope.close();
        httpRoot.end();

        provider.forceFlush().join(5, TimeUnit.SECONDS);
        List<SpanData> spans = exporter.getFinishedSpanItems();
        provider.close();

        SpanData http = byPrefix(spans, "http.request");
        SpanData chainSpan = byPrefix(spans, "chain.");
        SpanData llmSpan = byPrefix(spans, "llm.");
        SpanData toolSpan = byPrefix(spans, "tool.");

        // ---- tree shape (contract §6.2) ----
        assertThat(http.getParentSpanId()).isEqualTo("0000000000000000");
        assertThat(chainSpan.getTraceId()).isEqualTo(http.getTraceId());
        assertThat(chainSpan.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(llmSpan.getParentSpanId()).isEqualTo(chainSpan.getSpanId());
        assertThat(toolSpan.getParentSpanId()).isEqualTo(chainSpan.getSpanId());
        assertThat(http.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(llmSpan.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(chainSpan.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(toolSpan.getKind()).isEqualTo(SpanKind.INTERNAL);

        // ---- chain attributes (contract §7.1) ----
        var chainAttrs = chainSpan.getAttributes();
        assertThat(chainAttrs.get(str("gen_ai.system"))).isEqualTo("openjiuwen");
        assertThat(chainAttrs.get(str("openjiuwen.agent.invoke_type"))).isEqualTo("chain");
        assertThat(chainAttrs.get(str("openjiuwen.agent.name"))).isEqualTo("EDPAgent");
        assertThat(chainAttrs.get(str("openjiuwen.agent.inputs"))).isEqualTo("{\"query\":\"推荐理财产品\"}");
        assertThat(chainAttrs.get(str("openjiuwen.agent.outputs"))).contains("\"answer\":\"好的\"");
        assertThat(chainAttrs.get(str("openjiuwen.invoke_id"))).isEqualTo("inv-1");
        assertThat(chainAttrs.get(str("openjiuwen.parent_invoke_id"))).isEqualTo("");
        assertThat(chainAttrs.get(str("openjiuwen.child_invoke_ids"))).contains("inv-2");
        assertThat(chainAttrs.get(str("openjiuwen.trace.id"))).isEqualTo("trace-ct");
        assertThat(chainAttrs.get(str("session.id"))).isEqualTo("conv-ct");

        // ---- llm attributes (contract §7.2) ----
        var llmAttrs = llmSpan.getAttributes();
        assertThat(llmAttrs.get(str("gen_ai.system"))).isEqualTo("openjiuwen");
        assertThat(llmAttrs.get(str("gen_ai.operation.name"))).isEqualTo("chat");
        assertThat(llmAttrs.get(str("gen_ai.request.model"))).isEqualTo("model-x");
        assertThat(llmAttrs.get(str("gen_ai.prompt"))).contains("\"role\":\"user\"");
        assertThat(llmAttrs.get(str("gen_ai.completion"))).contains("好的");
        assertThat(llmAttrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
        assertThat(llmAttrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(7L);
        assertThat(llmAttrs.get(str("openjiuwen.llm.finish_reason"))).isEqualTo("stop");
        assertThat(llmAttrs.get(str("openjiuwen.agent.invoke_type"))).isEqualTo("llm");
        assertThat(llmAttrs.get(str("openjiuwen.parent_invoke_id"))).isEqualTo("inv-1");
        assertThat(llmAttrs.get(str("session.id"))).isEqualTo("conv-ct");

        // ---- tool attributes (contract §7.3) ----
        var toolAttrs = toolSpan.getAttributes();
        assertThat(toolAttrs.get(str("gen_ai.operation.name"))).isEqualTo("execute_tool");
        assertThat(toolAttrs.get(str("gen_ai.tool.name"))).isEqualTo("read_file");
        assertThat(toolAttrs.get(str("openjiuwen.agent.invoke_type"))).isEqualTo("plugin");
        assertThat(toolAttrs.get(str("openjiuwen.agent.inputs"))).contains("\"path\":\"/tmp/a.txt\"");
        assertThat(toolAttrs.get(str("openjiuwen.agent.outputs"))).contains("\"code\":0");
        assertThat(toolAttrs.get(str("session.id"))).isEqualTo("conv-ct");

        // ---- every span self-contained (contract §9.D) ----
        for (SpanData span : spans) {
            assertThat(span.getAttributes().get(str("session.id"))).as(span.getName()).isNotNull();
        }
    }

    private static SpanData byPrefix(List<SpanData> spans, String prefix) {
        return spans.stream().filter(s -> s.getName().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + prefix + "*"));
    }
}
