/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

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
 * Tests the deterministic Runtime Bus demo handler behavior.
 *
 * @since 2026-08-05
 */
class RuntimeBusDemoAgentHandlerTest {
    @Test
    void callerDelegatesThenReturnsRemoteResult() {
        RuntimeBusDemoAgentHandler handler = RuntimeBusDemoAgentHandler.caller();
        ServeRequest initial = request("hello");

        QueryResponse delegated = handler.query(initial);

        assertThat(delegated.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");

        ServeRequest resumed = request("hello");
        resumed.setMetadata(Map.of("runtime.remoteToolResults",
                Map.of(RuntimeBusDemoAgentHandler.TOOL_CALL_ID, "target result")));

        assertThat(handler.query(resumed).getResult().toString())
                .contains("source runtime received remote result: target result");
    }

    @Test
    void calleeReturnsDeterministicMarker() {
        QueryResponse response = RuntimeBusDemoAgentHandler.callee().query(request("hello"));

        assertThat(response.getResult().toString()).contains("target runtime received: hello");
    }

    @Test
    void calleeEchoesTraceAndAgentMetadataIntoResult() {
        ServeRequest request = request("hello");
        request.setMetadata(Map.of("attributes", Map.of("traceId", "trace-11"),
                "agentId", "demo-a2a-agent-a"));

        QueryResponse response = RuntimeBusDemoAgentHandler.callee().query(request);

        assertThat(response.getResult().toString())
                .contains("[trace=trace-11]")
                .contains("[agent=demo-a2a-agent-a]");
    }

    @Test
    void calleeRequestsInputForApprovalScenario() {
        QueryResponse response = RuntimeBusDemoAgentHandler.callee()
                .query(request(RuntimeBusDemoAgentHandler.INPUT_REQUIRED_TRIGGER));

        assertThat(response.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");
        assertThat(response.getResult().toString()).contains("Approve target runtime operation?");
    }

    @Test
    void calleeStreamsMultipleChunksBeforeCompletion() {
        List<String> chunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();

        RuntimeBusDemoAgentHandler.callee(3, 0L).streamQuery(request("hello"), new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk.getData().toString());
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).contains("target stream chunk 1/3: hello");
        assertThat(chunks.get(2)).contains("target stream chunk 3/3: hello");
        assertThat(completed).isTrue();
    }

    private static ServeRequest request(String text) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("runtime-bus-runtime-context");
        request.setMessages(java.util.List.of(Map.of("role", "user", "content", text)));
        request.setMetadata(Map.of());
        return request;
    }
}
