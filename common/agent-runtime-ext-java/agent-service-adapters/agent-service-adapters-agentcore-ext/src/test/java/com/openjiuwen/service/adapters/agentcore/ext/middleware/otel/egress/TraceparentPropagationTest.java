/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.a2a.client.A2AOutboundRequest;
import com.openjiuwen.service.app.controller.a2a.client.A2APropagationHeaderRegistry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * TraceparentResolver 的单元测试：traceparent 取值（当前线程 span / 按 body contextId 查暂存）
 * 以及注册进主仓 A2APropagationHeaderRegistry 后的端到端链路。
 */
class TraceparentPropagationTest {
    private static final String CONV = "conv-x";

    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
            .build();

    @AfterEach
    void cleanup() {
        EgressContextStash.remove(CONV);
        A2APropagationHeaderRegistry.registerProvider(null);
    }

    @Test
    void resolvesFromCurrentSpan() {
        Span span = provider.get("t").spanBuilder("dispatch").startSpan();
        Map<String, String> headers;
        try (Scope ignored = span.makeCurrent()) {
            headers = TraceparentResolver.provideHeaders(
                    new A2AOutboundRequest("http://x/a2a", "POST", "{\"jsonrpc\":\"2.0\"}"));
        } finally {
            span.end();
        }
        String expected = "00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-01";
        assertThat(headers).containsEntry("traceparent", expected);
    }

    @Test
    void emptyWhenNoContextAndNoStash() {
        assertThat(TraceparentResolver.provideHeaders(new A2AOutboundRequest("http://x/a2a", "POST", "{}"))).isEmpty();
        assertThat(TraceparentResolver.provideHeaders(new A2AOutboundRequest("http://x/a2a", "GET", null))).isEmpty();
    }

    @Test
    void fallsBackToStashByBodyContextId() {
        Span chain = provider.get("t").spanBuilder("chain").startSpan();
        EgressContextStash.put(CONV, chain.storeInContext(Context.root()));
        String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"contextId\":\"conv-x\"}}}";
        Map<String, String> headers = TraceparentResolver.provideHeaders(
                new A2AOutboundRequest("http://x/a2a", "POST", body));
        chain.end();
        String expected = "00-" + chain.getSpanContext().getTraceId() + "-"
                + chain.getSpanContext().getSpanId() + "-01";
        assertThat(headers).containsEntry("traceparent", expected);
    }

    @Test
    void outboundSlotWinsOverChainStash() {
        Span chain = provider.get("t").spanBuilder("chain").startSpan();
        Span dispatch = provider.get("t").spanBuilder("dispatch").startSpan();
        EgressContextStash.put(CONV, chain.storeInContext(Context.root()));
        EgressContextStash.putOutbound(CONV, dispatch.storeInContext(Context.root()));
        String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"contextId\":\"conv-x\"}}}";
        Map<String, String> headers = TraceparentResolver.provideHeaders(
                new A2AOutboundRequest("http://x/a2a", "POST", body));
        chain.end();
        dispatch.end();
        String expected = "00-" + dispatch.getSpanContext().getTraceId()
                + "-" + dispatch.getSpanContext().getSpanId() + "-01";
        assertThat(headers).containsEntry("traceparent", expected);
    }

    @Test
    void malformedBodyYieldsEmpty() {
        assertThat(TraceparentResolver.provideHeaders(
                new A2AOutboundRequest("http://x/a2a", "POST", "not-json"))).isEmpty();
        assertThat(TraceparentResolver.provideHeaders(
                new A2AOutboundRequest("http://x/a2a", "POST", "{\"params\":{}}"))).isEmpty();
    }

    @Test
    void registrationWiresIntoMainRepoRegistry() {
        A2APropagationHeaderRegistry.Registration registration = A2APropagationHeaderRegistry
                .registerProvider(TraceparentResolver::provideHeaders);
        Span span = provider.get("t").spanBuilder("dispatch").startSpan();
        Map<String, String> headers;
        try (Scope ignored = span.makeCurrent()) {
            headers = A2APropagationHeaderRegistry.provide(new A2AOutboundRequest("http://x/a2a", "POST", "{}"));
        } finally {
            span.end();
        }
        String expected = "00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId() + "-01";
        assertThat(headers).containsEntry("traceparent", expected);
        registration.close();
        assertThat(A2APropagationHeaderRegistry.provide(
                new A2AOutboundRequest("http://x/a2a", "POST", "{}"))).isEmpty();
    }
}
