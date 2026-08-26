/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TraceIdentityFilter 的单元测试：四级提取优先级、首跳注入、降级生成、空 body 放行、body 透传。
 */
class TraceIdentityFilterTest {
    private static final String CONV = "conv-1";
    private static final String TRACE_ID = "1cd0e9b4ebc1bc708de6aae571b089ce";
    private static final String SPAN_ID = "505c131920042a38";

    private final TraceContextCarrier carrier = TraceContextCarrier.create(86400L);

    @Test
    void headerWinsAsSource() throws Exception {
        runFilter(filter(null), request(body(CONV), "00-" + TRACE_ID + "-" + SPAN_ID + "-01", null));
        assertThat(carrier.find(CONV)).isPresent();
        assertThat(carrier.find(CONV).get().getTraceId()).isEqualTo(TRACE_ID);
        assertThat(carrier.find(CONV).get().isDegraded()).isFalse();
        assertThat(carrier.find(CONV).get().getIngressChannel()).isEqualTo("a2a");
    }

    @Test
    void metadataTraceKeyIsSecondSource() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"contextId\":\"conv-1\"},"
                + "\"metadata\":{\"trace_id\":\"" + TRACE_ID + "\",\"parent_run_id\":\"task-9#1\"}}}";
        runFilter(filter(null), request(body, null, null));
        assertThat(carrier.find(CONV).get().getTraceId()).isEqualTo(TRACE_ID);
        assertThat(carrier.find(CONV).get().getParentRunId()).contains("task-9#1");
    }

    @Test
    void carrierEntryIsReusedWhenNoInboundId() throws Exception {
        carrier.put(CONV, new TraceContextCarrier.Entry(TRACE_ID, false, "a2a", "t", Instant.now()));
        runFilter(filter(null), request(body(CONV), null, null));
        assertThat(carrier.find(CONV).get().getTraceId()).isEqualTo(TRACE_ID);
        assertThat(carrier.find(CONV).get().isDegraded()).isFalse();
    }

    @Test
    void fallsBackToGeneratedDegradedId() throws Exception {
        runFilter(filter(null), request(body(CONV), null, null));
        TraceContextCarrier.Entry entry = carrier.find(CONV).get();
        assertThat(entry.isDegraded()).isTrue();
        assertThat(entry.getTraceId()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void injectsContextIdWhenBothMissing() throws Exception {
        AtomicReference<String> downstream = new AtomicReference<>();
        TraceIdentityFilter filter = new TraceIdentityFilter(carrier, null, null);
        MockHttpServletRequest request = request("{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{}}}", null, null);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                downstream.set(new String(req.getInputStream().readAllBytes())));
        // 注入的 contextId 出现在下游 body，且 carrier 以其为键建档（首跳与后续轮共享）
        assertThat(downstream.get()).contains("\"contextId\":\"");
        String injected = downstream.get().replaceAll(".*\"contextId\":\"([^\"]+)\".*", "$1");
        assertThat(injected).hasSize(36);
        assertThat(carrier.find(injected)).isPresent();
    }

    @Test
    void noInjectionWhenTaskIdPresent() throws Exception {
        AtomicReference<String> downstream = new AtomicReference<>();
        String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"taskId\":\"task-7\"}}}";
        // TaskStore 查无此 task（如重启清空）→ 退回 taskId 本身作 carrier key
        org.a2aproject.sdk.server.tasks.TaskStore taskStore =
                org.mockito.Mockito.mock(org.a2aproject.sdk.server.tasks.TaskStore.class);
        org.mockito.Mockito.when(taskStore.get("task-7")).thenReturn(null);
        TraceIdentityFilter filter = new TraceIdentityFilter(carrier, taskStore, null);
        MockHttpServletRequest request = request(body, null, null);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                downstream.set(new String(req.getInputStream().readAllBytes())));
        // 带 taskId 的续跑请求不注入：body 原样，carrier 以 taskId 为键
        assertThat(downstream.get()).isEqualTo(body);
        assertThat(carrier.find("task-7")).isPresent();
    }

    @Test
    void blankBodyPassesThroughWithoutError() throws Exception {
        AtomicReference<String> downstream = new AtomicReference<>();
        TraceIdentityFilter filter = new TraceIdentityFilter(carrier, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/health");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                downstream.set(new String(req.getInputStream().readAllBytes())));
        assertThat(downstream.get()).isEmpty();
        assertThat(carrier.find(CONV)).isEmpty();
    }

    @Test
    void tenantAndRestChannelRecorded() throws Exception {
        MockHttpServletRequest request = request(body(CONV), "00-" + TRACE_ID + "-" + SPAN_ID + "-01", "tenant-7");
        request.setRequestURI("/v1/query");
        runFilter(filter(null), request);
        assertThat(carrier.find(CONV).get().getTenantId()).isEqualTo("tenant-7");
        assertThat(carrier.find(CONV).get().getIngressChannel()).isEqualTo("rest");
    }

    @Test
    void bodyIsPassedDownstreamIntact() throws Exception {
        AtomicReference<String> downstream = new AtomicReference<>();
        String body = body(CONV);
        TraceIdentityFilter filter = new TraceIdentityFilter(carrier, null, null);
        MockHttpServletRequest request = request(body, null, null);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                downstream.set(new String(req.getInputStream().readAllBytes())));
        assertThat(downstream.get()).isEqualTo(body);
    }

    private TraceIdentityFilter filter(RedisTrajectoryStore store) {
        return new TraceIdentityFilter(carrier, null, store);
    }

    private static String body(String conversationId) {
        return "{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"contextId\":\"" + conversationId + "\"}}}";
    }

    private static MockHttpServletRequest request(String body, String traceparent, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent(body.getBytes());
        if (traceparent != null) {
            request.addHeader("traceparent", traceparent);
        }
        if (tenant != null) {
            request.addHeader("x-tenant-id", tenant);
        }
        return request;
    }

    private static void runFilter(TraceIdentityFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
    }
}
