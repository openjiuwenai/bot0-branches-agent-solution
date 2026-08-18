/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import com.openjiuwen.extensions.tracerotel.OtelTracerSetup;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manual verification against the mock OTLP collector (otlp_relay.py serve on :4317).
 * Excluded from the default surefire run by the *IT suffix; run explicitly with:
 * mvn test -Dtest=OtlpRelayCheckIT
 */
class OtlpRelayCheckIT {
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
    void sendContractTreeToRelay() {
        OtelTracerConfig config = OtelTracerConfig.builder()
                .exporterType("otlp").exporterEndpoint("http://localhost:4317").protocol("grpc")
                .serviceName("edp-agent").isRedactionEnabled(false).maxAttrLength(-1).build();
        SpanExporter otlp = OtelTracerSetup.createOtlpExporter(config);
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config, otlp, "inst-relay");
        OtelJsonAgentHandler handler = new OtelJsonAgentHandler(provider.get(config.getTracerName()), config);

        SessionContextHolder.setCurrentSession(sessionOf("conv-relay"));

        Span httpRoot = provider.get("relay-http").spanBuilder("http.request")
                .setSpanKind(SpanKind.SERVER).startSpan();
        httpRoot.setAttribute("http.request.method", "POST");
        httpRoot.setAttribute("http.route", "/a2a");
        httpRoot.setAttribute("openjiuwen.http.request_body", "{\"conversationId\":\"conv-relay\"}");

        TraceAgentSpan chain = new TraceAgentSpan("trace-relay", "inv-1", "");
        chain.setInvokeType("chain");
        chain.setName("EDPAgent");
        chain.setChildInvokesId(List.of("inv-2", "inv-3"));
        TraceAgentSpan llm = new TraceAgentSpan("trace-relay", "inv-2", "inv-1");
        llm.setInvokeType("llm");
        llm.setName("model-x");
        TraceAgentSpan tool = new TraceAgentSpan("trace-relay", "inv-3", "inv-1");
        tool.setInvokeType("plugin");
        tool.setName("read_file");

        Scope scope = httpRoot.storeInContext(Context.root()).makeCurrent();
        handler.onChainStart(chain, Map.of("query", "推荐理财产品"), Map.of("class_name", "EDPAgent"));
        handler.onLlmStart(llm, List.of(Map.of("role", "user", "content", "推荐")), Map.of("class_name", "model-x"));
        handler.onLlmEnd(llm, Map.of("outputs", Map.of("role", "assistant", "content", "好的",
                "usage", Map.of("input_tokens", 5, "output_tokens", 7), "finish_reason", "stop")));
        handler.onPluginStart(tool, Map.of("inputs", Map.of("path", "/tmp/a.txt")), Map.of("class_name", "read_file"));
        handler.onPluginEnd(tool, Map.of("code", 0));
        handler.onChainEnd(chain, Map.of("answer", "好的"));
        scope.close();

        httpRoot.setAttribute("http.response.status_code", 200L);
        httpRoot.end();

        provider.forceFlush().join(10, TimeUnit.SECONDS);
        provider.close();
        org.slf4j.LoggerFactory.getLogger(OtlpRelayCheckIT.class).info("[relay-check] sent 4 spans to localhost:4317");
    }
}
