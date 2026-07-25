/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ReclassifyServeOrchestratorTest {
    private static final String PAYLOAD = "{\"code\":\"VERSATILE_INTENT_AMBIGUOUS\","
            + "\"intent_id\":\"1\",\"response_content\":\"无法确定\","
            + "\"ambiguous_intent_id\":\"1\"}";

    private ServeOrchestrator wrapped;
    private ReclassifyProperties properties;

    @BeforeEach
    void setUp() {
        wrapped = mock(ServeOrchestrator.class);
        properties = new ReclassifyProperties();
        properties.setEnabled(true);
        properties.setMaxReclassify(1);
    }

    private ServeRequest sampleRequest() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conv-1");
        request.setUserId("user-1");
        request.setStream(true);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "订一张北京到上海的机票");
        messages.add(userMsg);
        request.setMessages(messages);
        return request;
    }

    @Test
    void passthroughWhenDisabled() {
        properties.setEnabled(false);
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        decorator.streamQuery(sampleRequest(), observer);
        verify(wrapped, times(1)).streamQuery(any(), any());
    }

    @Test
    void streamReclassifySucceedsOnSecondAttempt() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver downstream = mock(QueryStreamObserver.class);

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(new RemoteAgentException(PAYLOAD, null));
            return null;
        }).doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final answer"));
            obs.onComplete();
            return null;
        }).when(wrapped).streamQuery(any(), any());

        decorator.streamQuery(sampleRequest(), downstream);

        verify(wrapped, times(2)).streamQuery(any(), any());
        ArgumentCaptor<QueryChunk> chunkCaptor = ArgumentCaptor.forClass(QueryChunk.class);
        verify(downstream, times(1)).onNext(chunkCaptor.capture());
        verify(downstream, times(1)).onComplete();
        verify(downstream, never()).onError(any());
        assertThat(chunkCaptor.getValue().getData()).isEqualTo("final answer");
    }

    @Test
    void streamReclassifyLimitProducesError() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver downstream = mock(QueryStreamObserver.class);

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(new RemoteAgentException(PAYLOAD, null));
            return null;
        }).when(wrapped).streamQuery(any(), any());

        decorator.streamQuery(sampleRequest(), downstream);

        verify(wrapped, times(2)).streamQuery(any(), any());
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(downstream, times(1)).onError(errorCaptor.capture());
        assertThat(errorCaptor.getValue().getMessage()).contains("VERSATILE_INTENT_RECLASSIFY_LIMIT");
    }

    @Test
    void streamForwardsNonAmbiguousError() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver downstream = mock(QueryStreamObserver.class);
        Throwable realError = new IllegalStateException("unrelated failure");

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(realError);
            return null;
        }).when(wrapped).streamQuery(any(), any());

        decorator.streamQuery(sampleRequest(), downstream);

        verify(wrapped, times(1)).streamQuery(any(), any());
        verify(downstream, times(1)).onError(realError);
    }

    @Test
    void streamMaxReclassifyZeroProducesLimitImmediately() {
        properties.setMaxReclassify(0);
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver downstream = mock(QueryStreamObserver.class);

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(new RemoteAgentException(PAYLOAD, null));
            return null;
        }).when(wrapped).streamQuery(any(), any());

        decorator.streamQuery(sampleRequest(), downstream);

        verify(wrapped, times(1)).streamQuery(any(), any());
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(downstream, times(1)).onError(errorCaptor.capture());
        assertThat(errorCaptor.getValue().getMessage()).contains("VERSATILE_INTENT_RECLASSIFY_LIMIT");
    }

    @Test
    void queryReclassifySucceedsOnSecondAttempt() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryResponse successResponse = new QueryResponse(Map.of("content", "final"), "conv-1");

        when(wrapped.query(any())).thenThrow(
                new IllegalStateException("Remote batch execution failed",
                        new RemoteAgentException(PAYLOAD, null)))
                .thenReturn(successResponse);

        QueryResponse response = decorator.query(sampleRequest());
        assertThat(response.getResult()).isEqualTo(Map.of("content", "final"));
        verify(wrapped, times(2)).query(any());
    }

    @Test
    void queryReclassifyLimitRethrows() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        when(wrapped.query(any())).thenThrow(
                new IllegalStateException("Remote batch execution failed",
                        new RemoteAgentException(PAYLOAD, null)));

        assertThatThrownBy(() -> decorator.query(sampleRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSATILE_INTENT_RECLASSIFY_LIMIT");
        verify(wrapped, times(2)).query(any());
    }

    @Test
    void queryForwardsNonAmbiguousException() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        IllegalStateException realError = new IllegalStateException("unrelated");
        when(wrapped.query(any())).thenThrow(realError);

        assertThatThrownBy(() -> decorator.query(sampleRequest()))
                .isSameAs(realError);
        verify(wrapped, times(1)).query(any());
    }

    @Test
    void queryPassthroughWhenDisabled() {
        properties.setEnabled(false);
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryResponse response = new QueryResponse(Map.of("content", "x"), "conv-1");
        when(wrapped.query(any())).thenReturn(response);

        assertThat(decorator.query(sampleRequest())).isSameAs(response);
        verify(wrapped, times(1)).query(any());
    }

    @Test
    void augmentedRequestAppendsAssistantMessageAndPreservesLastUserQuery() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        when(wrapped.query(any())).thenThrow(
                new IllegalStateException("Remote batch execution failed",
                        new RemoteAgentException(PAYLOAD, null)))
                .thenReturn(new QueryResponse(Map.of("content", "ok"), "conv-1"));

        ServeRequest original = sampleRequest();
        decorator.query(original);

        ArgumentCaptor<ServeRequest> reqCaptor = ArgumentCaptor.forClass(ServeRequest.class);
        verify(wrapped, times(2)).query(reqCaptor.capture());
        ServeRequest augmented = reqCaptor.getAllValues().get(1);
        assertThat(augmented.getMessages()).hasSize(2);
        assertThat(augmented.getMessages().get(0).get("role")).isEqualTo("user");
        assertThat(augmented.getMessages().get(1).get("role")).isEqualTo("assistant");
        assertThat(augmented.getMessages().get(1).get("content")).isEqualTo("无法确定");
        assertThat(augmented.lastUserQuery()).isEqualTo("订一张北京到上海的机票");
        // Original request must not be mutated by the retry path.
        assertThat(original.getMessages()).hasSize(1);
    }

    @Test
    void queryMaxReclassifyZeroProducesLimitImmediately() {
        properties.setMaxReclassify(0);
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        when(wrapped.query(any())).thenThrow(
                new IllegalStateException("Remote batch execution failed",
                        new RemoteAgentException(PAYLOAD, null)));

        assertThatThrownBy(() -> decorator.query(sampleRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSATILE_INTENT_RECLASSIFY_LIMIT");
        verify(wrapped, times(1)).query(any());
    }

    @Test
    void cancelAndResetDelegateToWrapped() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        decorator.cancelActive("conv-1");
        decorator.resetConversation("conv-1");
        verify(wrapped, times(1)).cancelActive("conv-1");
        verify(wrapped, times(1)).resetConversation("conv-1");
    }
}
