/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscaller;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests the source Runtime demo independently from the callee.
 *
 * @since 2026-08-12
 */
class CallerAgentHandlerTest {
    @Test
    void delegatesThenReturnsRemoteResult() {
        CallerAgentHandler handler = new CallerAgentHandler();
        QueryResponse delegated = handler.query(request("hello"));

        assertThat(delegated.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");
        assertThat(delegated.getResult().toString()).contains(CallerAgentHandler.TARGET_AGENT_ID);

        ServeRequest resumed = request("hello");
        resumed.setMetadata(Map.of("runtime.remoteToolResults",
                Map.of(CallerAgentHandler.TOOL_CALL_ID, "target result")));

        assertThat(handler.query(resumed).getResult().toString())
                .contains("source runtime received remote result: target result");
    }

    @Test
    void streamingDelegatesAndCompletesAfterRemoteResult() {
        CallerAgentHandler handler = new CallerAgentHandler();
        List<QueryChunk> delegated = new ArrayList<>();
        handler.streamQuery(request("hello"), observer(delegated, new AtomicBoolean()));
        assertThat(delegated).singleElement().extracting(QueryChunk::getType)
                .isEqualTo(QueryChunk.TYPE_INTERRUPT);

        ServeRequest resumed = request("hello");
        resumed.setMetadata(Map.of("runtime.remoteToolResults",
                Map.of(CallerAgentHandler.TOOL_CALL_ID, "target result")));
        List<QueryChunk> chunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        handler.streamQuery(resumed, observer(chunks, completed));

        assertThat(chunks).singleElement().extracting(QueryChunk::getData)
                .asString().contains("source runtime received remote result: target result");
        assertThat(completed).isTrue();
    }

    private static QueryStreamObserver observer(List<QueryChunk> chunks, AtomicBoolean completed) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        };
    }

    private static ServeRequest request(String text) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("runtime-bus-caller-context");
        request.setMessages(List.of(Map.of("role", "user", "content", text)));
        request.setMetadata(Map.of());
        return request;
    }
}
