/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.StreamingEventKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Verifies HTTP parsing, content negotiation, and stable Custom REST responses.
 *
 * @since 0.1.0
 */
class CustomRestHandlerTest {
    @Test
    void nonSerializableBlockingProjectionUsesStableAdapterErrorResponse() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        var command = mock(CustomRestProtocolAdapter.A2ASendCommand.class);
        when(command.stream()).thenReturn(false);
        when(prepared.command()).thenReturn(command);
        when(bridge.prepare(any(), anyBoolean())).thenReturn(prepared);
        when(bridge.executeBlocking(prepared)).thenReturn(new SelfReference());
        when(bridge.projectError(any(), any())).thenReturn(Map.of("error", "safe"));
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());

        var response = handler.handle(jsonRequest(), Map.of("conversation_id", "conversation"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "safe"));
        ArgumentCaptor<CustomRestFailure> failure = ArgumentCaptor.forClass(CustomRestFailure.class);
        verify(bridge).projectError(failure.capture(), any());
        assertThat(failure.getValue().getCode()).isEqualTo("adapter_execution_failed");
    }

    @Test
    void capturesLowercaseMultiValueHeadersQueryPathAndBody() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = blockingPrepared();
        when(bridge.prepare(any(), anyBoolean())).thenReturn(prepared);
        when(bridge.executeBlocking(prepared)).thenReturn(Map.of("ok", true));
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());
        MockHttpServletRequest request = jsonRequest();
        request.addHeader("X-Test", "one");
        request.addHeader("X-Test", "two");
        request.setParameter("q", "a", "b");

        var response = handler.handle(request, Map.of("conversation_id", "conversation"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<CustomRestProtocolAdapter.Context> context =
                ArgumentCaptor.forClass(CustomRestProtocolAdapter.Context.class);
        verify(bridge).prepare(context.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(context.getValue().headers()).containsEntry("x-test", java.util.List.of("one", "two"));
        assertThat(context.getValue().queryParams()).containsEntry("q", java.util.List.of("a", "b"));
        assertThat(context.getValue().pathVariables()).containsEntry("conversation_id", "conversation");
        assertThat(context.getValue().body()).containsEntry("input", "hello");
    }

    @Test
    void acceptsSseWhenAnyAcceptHeaderValueAllowsIt() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = blockingPrepared();
        when(bridge.prepare(any(), anyBoolean())).thenReturn(prepared);
        when(bridge.executeBlocking(prepared)).thenReturn(Map.of("ok", true));
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());
        MockHttpServletRequest request = jsonRequest();
        request.removeHeader("Accept");
        request.addHeader("Accept", "application/json");
        request.addHeader("Accept", "text/event-stream");

        handler.handle(request, Map.of("conversation_id", "conversation"));

        verify(bridge).prepare(any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void rejectsSseWhenSpecificMediaRangeHasZeroQuality() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = blockingPrepared();
        when(bridge.prepare(any(), anyBoolean())).thenReturn(prepared);
        when(bridge.executeBlocking(prepared)).thenReturn(Map.of("ok", true));
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());
        MockHttpServletRequest request = jsonRequest();
        request.removeHeader(HttpHeaders.ACCEPT);
        request.addHeader(HttpHeaders.ACCEPT, "text/event-stream;q=0, */*;q=1");

        handler.handle(request, Map.of("conversation_id", "conversation"));

        verify(bridge).prepare(any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void streamingResponseDisablesProxyCachingAndBuffering() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        var command = mock(CustomRestProtocolAdapter.A2ASendCommand.class);
        when(command.stream()).thenReturn(true);
        when(prepared.command()).thenReturn(command);
        when(bridge.prepare(any(), anyBoolean())).thenReturn(prepared);
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> {
        };
        when(bridge.executeStream(prepared)).thenReturn(publisher);
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());
        MockHttpServletRequest request = jsonRequest();
        request.removeHeader(HttpHeaders.ACCEPT);
        request.addHeader(HttpHeaders.ACCEPT, "text/event-stream");

        var response = handler.handle(request, Map.of("conversation_id", "conversation"));

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-transform");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONNECTION)).isEqualTo("keep-alive");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void rejectsNonJsonBodyBeforeCallingAdapter() throws Exception {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        when(bridge.projectError(any(), any())).thenReturn(Map.of("error", "unsupported"));
        var handler = new CustomRestAutoConfiguration.CustomRestHandler(bridge, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/custom/conversation");
        request.setContentType("text/plain");
        request.setContent("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var response = handler.handle(request, Map.of("conversation_id", "conversation"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        verify(bridge, never()).prepare(any(), anyBoolean());
    }

    private static CustomRestA2ABridge.Prepared blockingPrepared() {
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        var command = mock(CustomRestProtocolAdapter.A2ASendCommand.class);
        when(command.stream()).thenReturn(false);
        when(prepared.command()).thenReturn(command);
        return prepared;
    }

    private static MockHttpServletRequest jsonRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/custom/conversation");
        request.setContentType("application/json");
        request.setContent("{\"input\":\"hello\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Accept", "application/json");
        return request;
    }

    private static final class SelfReference {
        /**
         * Returns this object to create a serialization cycle for the test.
         *
         * @return this object
         */
        public SelfReference getSelf() {
            return this;
        }
    }
}
