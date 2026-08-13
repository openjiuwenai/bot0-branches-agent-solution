/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscallee;

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
 * Tests the target Runtime demo independently from the caller.
 *
 * @since 2026-08-12
 */
class CalleeAgentHandlerTest {
    @Test
    void returnsDeterministicMarker() {
        QueryResponse response = CalleeAgentHandler.standard().query(request("hello"));
        assertThat(response.getResult().toString()).contains("target runtime received: hello");
    }

    @Test
    void echoesTraceAndAgentMetadataIntoResult() {
        ServeRequest request = request("hello");
        request.setMetadata(Map.of("attributes", Map.of("traceId", "trace-11"),
                "agentId", "demo-a2a-agent-a"));

        QueryResponse response = CalleeAgentHandler.standard().query(request);

        assertThat(response.getResult().toString())
                .contains("[trace=trace-11]")
                .contains("[agent=demo-a2a-agent-a]");
    }

    @Test
    void requestsInputForBothDocumentedTriggers() {
        assertInputRequired(CalleeAgentHandler.INPUT_REQUIRED_TRIGGER);
        assertInputRequired(CalleeAgentHandler.CLIENT_VERIFICATION_INPUT_TRIGGER);
    }

    @Test
    void clientToolSequenceAdvancesAcrossContinuationRounds() {
        CalleeAgentHandler handler = CalleeAgentHandler.standard();
        ServeRequest initial = request("please read the page then submit the order",
                CalleeAgentHandler.CLIENT_TOOL_SEQUENCE_CONVERSATION);
        List<QueryChunk> first = stream(handler, initial);
        assertThat(first).singleElement().extracting(QueryChunk::getData).asString().contains("readPage");

        ServeRequest readPageDone = request("read page result", CalleeAgentHandler.CLIENT_TOOL_SEQUENCE_CONVERSATION);
        readPageDone.setMetadata(Map.of("_interrupt", Map.of("toolName", "readPage")));
        assertThat(handler.query(readPageDone).getResult().toString()).contains("submitOrder");

        ServeRequest orderDone = request("submit order result", CalleeAgentHandler.CLIENT_TOOL_SEQUENCE_CONVERSATION);
        orderDone.setMetadata(Map.of("_interrupt", Map.of("toolName", "submitOrder")));
        assertThat(handler.query(orderDone).getResult().toString()).contains("order submitted successfully");
    }

    @Test
    void streamsMultipleChunksBeforeCompletion() {
        List<QueryChunk> chunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();

        CalleeAgentHandler.withStream(3, 0L).streamQuery(request("hello"), observer(chunks, completed));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getData().toString()).contains("target stream chunk 1/3: hello");
        assertThat(chunks.get(2).getData().toString()).contains("target stream chunk 3/3: hello");
        assertThat(completed).isTrue();
    }

    private static void assertInputRequired(String input) {
        QueryResponse response = CalleeAgentHandler.standard().query(request(input));
        assertThat(response.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");
        assertThat(response.getResult().toString())
                .contains("Approve target runtime operation?")
                .contains("_interrupt_kind=user_input");
    }

    private static List<QueryChunk> stream(CalleeAgentHandler handler, ServeRequest request) {
        List<QueryChunk> chunks = new ArrayList<>();
        handler.streamQuery(request, observer(chunks, new AtomicBoolean()));
        return chunks;
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
        return request(text, "runtime-bus-callee-context");
    }

    private static ServeRequest request(String text, String conversationId) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", text)));
        request.setMetadata(Map.of());
        return request;
    }
}
