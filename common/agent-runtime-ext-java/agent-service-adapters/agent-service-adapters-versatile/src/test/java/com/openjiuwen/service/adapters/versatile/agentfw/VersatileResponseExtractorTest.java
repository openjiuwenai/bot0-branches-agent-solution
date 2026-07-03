/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.spec.dto.QueryChunk;
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
    @Test
    void emitsInterruptWhenStreamEndsBeforeEndSignal() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine("data: {\"event\":\"message\"}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_INTERRUPT);
        assertThat(chunks.get(0).getData()).isEqualTo("{\"event\":\"message\"}");
        assertThat(chunks.get(1).getData()).isNull();
    }

    @Test
    void extractsResultNodeAndEmitsAnswerOnEnd() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

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
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

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
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

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
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"message\":\"boom\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_ERROR);
        assertThat(chunks.get(1).getData()).asString().contains("exception");
    }

    @Test
    void emitsNoFinalChunkWhenCompletedWithoutResult() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

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

    private static void assertAnswerEnvelope(QueryChunk chunk, String expectedOutput) {
        assertThat(chunk.getData()).isInstanceOf(Map.class);
        Map<?, ?> envelope = (Map<?, ?>) chunk.getData();
        assertThat(envelope.get("type")).isEqualTo("answer");
        assertThat(envelope.get("output")).isEqualTo(expectedOutput);
        assertThat(envelope.containsKey("payload")).isFalse();
    }
}
