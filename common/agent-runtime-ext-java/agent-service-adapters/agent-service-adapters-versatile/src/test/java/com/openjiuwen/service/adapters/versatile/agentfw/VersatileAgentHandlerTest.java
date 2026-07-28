/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests Versatile agent handler request and response adaptation.
 *
 * @since 2026-06-30
 */
class VersatileAgentHandlerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Tag("smoke")
    @Test
    void queryReturnsExtractedResult() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"data\":{\"node_type\":\"QA\",\"node_name\":\"AnswerNode\",\"text\":\"final\"}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        QueryResponse response = handler.query(request());

        assertThat(response.getConversationId()).isEqualTo("c-1");
        assertThat(response.getResult()).isEqualTo(Map.of("role", "assistant", "content", "final"));
    }

    @Test
    void streamQueryForwardsChunksAndCompletion() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"event\":\"message\",\"data\":{\"text\":\"thinking\"}}",
                "{\"data\":{\"node_type\":\"QA\",\"node_name\":\"AnswerNode\",\"text\":\"final\"}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);
        RecordingObserver observer = new RecordingObserver();

        handler.streamQuery(request(), observer);

        assertThat(observer.isCompleted).isTrue();
        assertThat(observer.error).isNull();
        assertThat(observer.chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertThat(observer.chunks.get(2).getData()).isInstanceOf(Map.class);
        Map<?, ?> envelope = (Map<?, ?>) observer.chunks.get(2).getData();
        assertThat(envelope.get("type")).isEqualTo("answer");
        assertThat(envelope.get("output")).isEqualTo("final");
        assertThat(envelope.containsKey("payload")).isFalse();
    }

    @Test
    void streamQueryStopsWhenObserverIsCancelled() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"event\":\"message\",\"data\":{\"text\":\"first\"}}",
                "{\"event\":\"message\",\"data\":{\"text\":\"second\"}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);
        RecordingObserver observer = new RecordingObserver();
        observer.shouldCancelAfterFirstChunk = true;

        handler.streamQuery(request(), observer);

        assertThat(observer.chunks).hasSize(1);
        assertThat(observer.isCompleted).isFalse();
        assertThat(observer.error).isNull();
    }

    @Test
    void queryReturnsFallbackWhenCompletedWithoutResult() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"event\":\"message\",\"data\":{\"text\":\"only passthrough\"}}",
                "{\"event\":\"message\",\"data\":{\"summary\":\"need account confirmation\"}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        QueryResponse response = handler.query(request());

        assertThat(response.getConversationId()).isEqualTo("c-1");
        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("role")).isEqualTo("assistant");
        String expectedMessage = "{\"event\":\"message\",\"data\":{\"text\":\"only passthrough\"}}\n"
                + "{\"event\":\"message\",\"data\":{\"summary\":\"need account confirmation\"}}\n"
                + "{\"data\":{\"node_type\":\"End\"}}";
        assertThat(result.get("content")).isEqualTo(expectedMessage);
        assertThat(result.containsKey("_interrupt")).isFalse();
    }

    @Test
    void throwsWhenResultNodeArrivesWithoutEndSignal() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"data\":{\"node_type\":\"QA\",\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        assertThatThrownBy(() -> handler.query(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STREAM_CLOSED_WITHOUT_TERMINAL");
    }

    @Test
    void throwsWhenRemoteReturnsNoEvents() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of());
        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        assertThatThrownBy(() -> handler.query(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STREAM_CLOSED_WITHOUT_TERMINAL");
    }

    @Test
    void writesThreeFieldResultIntoQueryResponseResultMap() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":{\"node_type\":\"QA\","
                        + "\"response_content\":\"酒店预订\","
                        + "\"intent_id\":\"intent_L1_hotel\","
                        + "\"agent_id\":\"agent_card_L2_hotel\"}}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileProperties.ResultExtraction rc = new VersatileProperties.ResultExtraction();
        rc.setMatch("response_content");
        rc.setGet("/custom_rsp_data/data/response_content");
        properties.getResultExtractions().add(rc);
        VersatileProperties.ResultExtraction ii = new VersatileProperties.ResultExtraction();
        ii.setMatch("intent_id");
        ii.setGet("/custom_rsp_data/data/intent_id");
        properties.getResultExtractions().add(ii);
        VersatileProperties.ResultExtraction ai = new VersatileProperties.ResultExtraction();
        ai.setMatch("agent_id");
        ai.setGet("/custom_rsp_data/data/agent_id");
        properties.getResultExtractions().add(ai);

        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        QueryResponse response = handler.query(request());

        assertThat(response.getResult()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("role", "assistant")
                .containsEntry("content", "酒店预订");
        @SuppressWarnings("unchecked")
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).isNotNull();
        assertThat(interrupt).containsEntry("agentName", "agent_card_L2_hotel")
                .containsEntry("responseContent", "酒店预订");
        Map<?, ?> context = (Map<?, ?>) interrupt.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
    }

    @Test
    void resolvesAgentIdViaIntentAgentMappingWhenWorkflowOmitsIt() throws Exception {
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":{\"node_type\":\"QA\","
                        + "\"response_content\":\"酒店预订\","
                        + "\"intent_id\":\"intent_L1_hotel\"}}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileProperties.ResultExtraction rc = new VersatileProperties.ResultExtraction();
        rc.setMatch("response_content");
        rc.setGet("/custom_rsp_data/data/response_content");
        properties.getResultExtractions().add(rc);
        VersatileProperties.ResultExtraction ii = new VersatileProperties.ResultExtraction();
        ii.setMatch("intent_id");
        ii.setGet("/custom_rsp_data/data/intent_id");
        properties.getResultExtractions().add(ii);
        // No agent_id extraction — mapping must resolve
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_L2_hotel");
        properties.getIntentAgentMapping().put("intent_L1_hotel", List.of(candidate));

        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        QueryResponse response = handler.query(request());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        @SuppressWarnings("unchecked")
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).isNotNull();
        assertThat(interrupt).containsEntry("agentName", "agent_card_L2_hotel")
                .containsEntry("responseContent", "酒店预订");
    }

    @Test
    void writesA2aDelegateWithEmptyResponseContentWhenWorkflowOmitsIt() throws Exception {
        // Reference scenario: intent recognition node returns only intent_id + agent_id,
        // no response_content. Adapter must still produce a2a_delegate with empty
        // responseContent (Caller will not append an assistant message).
        VersatileProperties properties = propertiesWithServer(List.of(
                "{\"data\":{\"node_name\":\"AnswerNode\",\"node_type\":\"Code\","
                        + "\"outputs\":{\"intent_id\":\"intent_L1_hotel\","
                        + "\"agent_id\":\"agent_card_L2_hotel\"}}}",
                "{\"data\":{\"node_type\":\"End\"}}"
        ));
        properties.setResultNodeName("AnswerNode");
        VersatileProperties.ResultExtraction ii = new VersatileProperties.ResultExtraction();
        ii.setMatch("intent_id");
        ii.setGet("/data/outputs/intent_id");
        properties.getResultExtractions().add(ii);
        VersatileProperties.ResultExtraction ai = new VersatileProperties.ResultExtraction();
        ai.setMatch("agent_id");
        ai.setGet("/data/outputs/agent_id");
        properties.getResultExtractions().add(ai);

        VersatileAgentHandler handler = new VersatileAgentHandler(properties);

        QueryResponse response = handler.query(request());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        @SuppressWarnings("unchecked")
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).isNotNull();
        assertThat(interrupt).containsEntry("agentName", "agent_card_L2_hotel")
                .containsEntry("responseContent", "");
        Map<?, ?> context = (Map<?, ?>) interrupt.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
    }

    private static ServeRequest request() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "q"))));
        request.setMetadata(Map.of("body", Map.of("custom_data", Map.of())));
        return request;
    }

    private VersatileProperties propertiesWithServer(List<String> lines) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/run", exchange -> {
            byte[] body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        VersatileProperties properties = new VersatileProperties();
        properties.setUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort() + "/run");
        VersatileProperties.Intent intent = new VersatileProperties.Intent();
        intent.setId("i1");
        intent.setName("n1");
        properties.setIntents(List.of(intent));
        return properties;
    }

    private static final class RecordingObserver implements QueryStreamObserver {
        private final List<QueryChunk> chunks = new ArrayList<>();
        private Throwable error;
        private boolean isCompleted;
        private boolean shouldCancelAfterFirstChunk;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onComplete() {
            this.isCompleted = true;
        }

        @Override
        public boolean isCancelled() {
            return shouldCancelAfterFirstChunk && !chunks.isEmpty();
        }
    }
}
