/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InProcessRemoteAgentCallerTest {
    @Test
    void callsTargetHandlerAndForwardsChunks() {
        AgentHandler target = mock(AgentHandler.class);
        doAnswerStream(target, List.of(
                new QueryChunk(QueryChunk.TYPE_CHUNK, "二层中间"),
                new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("type", "answer", "output", "二层最终"))));

        InProcessRemoteAgentCaller caller = new InProcessRemoteAgentCaller(
                Map.of("agent_card_L2_hotel", target));

        List<QueryChunk> sink = new ArrayList<>();
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));
        caller.call(new RemoteAgentCall("agent_card_L2_hotel", request, "一层输出", "c-1", null),
                capturingObserver(sink));

        assertThat(sink).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertThat(sink.get(1).getData()).isInstanceOf(Map.class);
    }

    @Test
    void appendsResponseContentAsAssistantMessageBeforeDispatch() {
        AgentHandler target = mock(AgentHandler.class);
        doAnswer(inv -> null).when(target).streamQuery(any(), any());

        InProcessRemoteAgentCaller caller = new InProcessRemoteAgentCaller(
                Map.of("agent_card_L2_hotel", target));

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));
        caller.call(new RemoteAgentCall("agent_card_L2_hotel", request, "一层输出", "c-1", null),
                capturingObserver(new ArrayList<>()));

        org.mockito.ArgumentCaptor<ServeRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ServeRequest.class);
        verify(target).streamQuery(captor.capture(), any());
        ServeRequest forwarded = captor.getValue();
        assertThat(forwarded.getMessages()).hasSize(2);
        assertThat(forwarded.getMessages().get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "一层输出");
        assertThat(forwarded.lastUserQuery()).isEqualTo("订酒店");
    }

    @Test
    void reportsErrorWhenAgentIdNotRegistered() {
        InProcessRemoteAgentCaller caller = new InProcessRemoteAgentCaller(Map.of());
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        caller.call(new RemoteAgentCall("missing", new ServeRequest()),
                new QueryStreamObserver() {
                    @Override public void onNext(QueryChunk chunk) { }
                    @Override public void onComplete() { }
                    @Override public void onError(Throwable e) { errorRef.set(e); }
                    @Override public boolean isCancelled() { return false; }
                });
        assertThat(errorRef.get()).isInstanceOf(RemoteAgentException.class)
                .hasMessageContaining("VERSATILE_INPROCESS_AGENT_NOT_FOUND");
    }

    private static void doAnswerStream(AgentHandler mockHandler, List<QueryChunk> chunks) {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            for (QueryChunk c : chunks) {
                obs.onNext(c);
            }
            obs.onComplete();
            return null;
        }).when(mockHandler).streamQuery(any(), any());
    }

    private static QueryStreamObserver capturingObserver(List<QueryChunk> sink) {
        return new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { sink.add(chunk); }
            @Override public void onComplete() { }
            @Override public void onError(Throwable e) { }
            @Override public boolean isCancelled() { return false; }
        };
    }
}
