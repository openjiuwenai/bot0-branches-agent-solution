/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.handoff;

import com.openjiuwen.service.adapters.versatile.agentfw.IntentAgentResolver;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileResponseExtractor;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check: the mock controller's node_finished SSE lines must be recognized
 * by the FEAT-002 baseline extractor (answer + terminal End marker).
 *
 * @since 0.1.0
 */
class MockWireFormatExtractionTest {

    @Test
    void answerAndEndLinesProduceLegacyAnswer() {
        VersatileProperties properties = new VersatileProperties();
        properties.setResultNodeName("AnswerNode");
        VersatileResponseExtractor extractor =
                new VersatileResponseExtractor(properties, new IntentAgentResolver(properties));
        extractor.consumeLine("data: {\"event\":\"node_finished\",\"data\":{"
                + "\"node_name\":\"AnswerNode\",\"node_type\":\"QA\","
                + "\"outputs\":{\"response\":\"一级本地业务答案\"},\"text\":\"一级本地业务答案\"}}");
        extractor.consumeLine("data: {\"event\":\"workflow_finished\",\"data\":{"
                + "\"node_id\":\"node_end\",\"node_type\":\"End\",\"node_status\":\"node_finished\"}}");
        List<QueryChunk> finish = extractor.finish();
        assertThat(finish).hasSize(1);
        assertThat(String.valueOf(finish.get(0).getData())).contains("一级本地业务答案");
    }

    @Test
    void exceptionLineFails() {
        VersatileProperties properties = new VersatileProperties();
        VersatileResponseExtractor extractor =
                new VersatileResponseExtractor(properties, new IntentAgentResolver(properties));
        extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"code\":500}}");
        List<QueryChunk> finish = extractor.finish();
        assertThat(finish).hasSize(1);
        assertThat(finish.get(0).getType()).isEqualTo(QueryChunk.TYPE_ERROR);
    }
}
