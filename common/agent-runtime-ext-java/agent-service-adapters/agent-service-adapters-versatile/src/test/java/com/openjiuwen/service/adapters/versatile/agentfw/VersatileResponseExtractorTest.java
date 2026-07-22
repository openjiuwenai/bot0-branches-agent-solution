/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Versatile streaming response extraction rules.
 *
 * @since 2026-06-30
 */
class VersatileResponseExtractorTest {
    private static VersatileProperties props(String resultNodeName) {
        VersatileProperties p = new VersatileProperties();
        p.setResultNodeName(resultNodeName);
        return p;
    }

    private static IntentAgentResolver resolver(VersatileProperties props) {
        return new IntentAgentResolver(props);
    }

    @Test
    void emitsInterruptWhenStreamEndsBeforeEndSignal() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine("data: {\"event\":\"message\"}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_INTERRUPT);
        assertThat(chunks.get(0).getData()).isEqualTo("{\"event\":\"message\"}");
        assertThat(chunks.get(1).getData()).isNull();
    }

    @Tag("smoke")
    @Test
    void extractsResultNodeAndEmitsAnswerOnEnd() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertAnswerEnvelope(chunks.get(1), "final");
    }

    @Test
    void emitsInterruptWhenResultNodeArrivesWithoutEndSignal() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = extractor.finish();

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_INTERRUPT);
        assertThat(chunks.get(0).getData()).isNull();
    }

    @Test
    void extractsResultFromCustomResponseData() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"text\":\"custom final\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertAnswerEnvelope(chunks.get(1), "custom final");
    }

    @Test
    void marksExceptionAsFailed() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"message\":\"boom\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_ERROR);
        assertThat(chunks.get(1).getData()).asString().contains("exception");
    }

    @Test
    void emitsNoFinalChunkWhenCompletedWithoutResult() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"\",\"summary\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK);
        assertThat(chunks.get(0).getData()).asString().contains("\"node_type\":\"End\"");
    }

    @Test
    void extractsThreeFieldResultWhenConfigured() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(props, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"response_content\":\"酒店预订\","
                + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_CHUNK);
        QueryChunk answer = chunks.stream()
                .filter(c -> c.getData() instanceof Map<?, ?> m && "answer".equals(m.get("type")))
                .findFirst().orElseThrow();
        Map<?, ?> envelope = (Map<?, ?>) answer.getData();
        assertThat(envelope.get("response_content")).isEqualTo("酒店预订");
        assertThat(envelope.get("intent_id")).isEqualTo("intent_L1_hotel");
        assertThat(envelope.get("agent_id")).isEqualTo("agent_card_L2_hotel");
    }

    @Test
    void emitsErrorWhenResponseContentMissing() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"intent_id\":\"intent_L1_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = extractor.finish();

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(0).getData()))
                .contains("VERSATILE_INTENT_RESULT_CONTRACT");
    }

    private static void addExtraction(VersatileProperties props, String match, String get) {
        VersatileProperties.ResultExtraction extraction = new VersatileProperties.ResultExtraction();
        extraction.setMatch(match);
        extraction.setGet(get);
        props.getResultExtractions().add(extraction);
    }

    private static void assertAnswerEnvelope(QueryChunk chunk, String expectedOutput) {
        assertThat(chunk.getData()).isInstanceOf(Map.class);
        Map<?, ?> envelope = (Map<?, ?>) chunk.getData();
        assertThat(envelope.get("type")).isEqualTo("answer");
        assertThat(envelope.get("output")).isEqualTo(expectedOutput);
        assertThat(envelope.containsKey("payload")).isFalse();
    }
}
