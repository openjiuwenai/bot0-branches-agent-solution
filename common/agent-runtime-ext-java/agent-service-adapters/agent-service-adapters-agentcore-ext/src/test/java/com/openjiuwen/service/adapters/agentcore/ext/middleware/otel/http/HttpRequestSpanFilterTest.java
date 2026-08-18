/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.HttpContextBridge;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

/**
 * HttpRequestSpanFilter 入口根 span 的单元测试。
 */
class HttpRequestSpanFilterTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final HttpContextBridge bridge = new HttpContextBridge();

    private HttpRequestSpanFilter filter() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return new HttpRequestSpanFilter(provider.get("test"), bridge);
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    @Test
    void syncRequest_createsServerRootSpanWithAttributes() throws Exception {
        MockHttpServletRequest request = request("{\"conversationId\":\"conv-1\",\"message\":\"hi\"}");
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("http.request");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.request.method"))).isEqualTo("POST");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("conv-1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("openjiuwen.http.request_body")))
                .contains("conv-1");
    }

    @Test
    void missingConversationId_noSpan() throws Exception {
        MockHttpServletRequest request = request("{\"message\":\"hi\"}");
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void invalidJsonBody_noSpan() throws Exception {
        MockHttpServletRequest request = request("not-json");
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void upstreamTraceparent_becomesParent() throws Exception {
        MockHttpServletRequest request = request("{\"conversationId\":\"conv-2\"}");
        request.addHeader("traceparent",
                "00-11111111111111111111111111111111-2222222222222222-01");
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getTraceId()).isEqualTo("11111111111111111111111111111111");
        assertThat(span.getParentSpanId()).isEqualTo("2222222222222222");
    }

    @Test
    void bridgeEntry_registeredDuringChainAndRemovedAfter() throws Exception {
        MockHttpServletRequest request = request("{\"conversationId\":\"conv-3\"}");
        final boolean[] presentDuringChain = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                presentDuringChain[0] = bridge.get("conv-3") != null;
            }
        };
        filter().doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(presentDuringChain[0]).isTrue();
        assertThat(bridge.get("conv-3")).isNull();
    }

    @Test
    void a2aNestedContextId_createsSpan() throws Exception {
        MockHttpServletRequest request = request(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"sendStreamingMessage\","
                        + "\"params\":{\"message\":{\"messageId\":\"m1\",\"contextId\":\"ctx-9\","
                        + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}}");
        filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.stringKey("session.id"))).isEqualTo("ctx-9");
    }

    @Test
    void downstream_canStillReadBody() throws Exception {
        String body = "{\"conversationId\":\"conv-read\"}";
        MockHttpServletRequest request = request(body);
        final String[] readByChain = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                readByChain[0] = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        };
        filter().doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(readByChain[0]).isEqualTo(body);
    }

    @Test
    void responseStatusCode_writtenOnSpan() throws Exception {
        MockHttpServletRequest request = request("{\"conversationId\":\"conv-4\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        filter().doFilter(request, response, new MockFilterChain());
        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
    }
}
