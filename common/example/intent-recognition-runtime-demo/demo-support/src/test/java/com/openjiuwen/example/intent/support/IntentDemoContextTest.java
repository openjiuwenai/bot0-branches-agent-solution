/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

class IntentDemoContextTest {
    @Test
    void buildsStandardA2ACatalogAndSharesSelectedCard() {
        IntentDemoContext context = IntentDemoContext.create(IntentDemoPropertiesTest.configuredProperties(),
                orderFirstReranker());

        IntentRecognitionResult<AgentCard> result = context.recognizer().recognize("查询订单物流");

        assertThat(context.agentCards()).extracting(AgentCard::name).containsExactly("Order Agent", "Weather Agent",
                "Knowledge Agent");
        assertThat(result.matched()).isTrue();
        assertThat(result.target()).isNotSameAs(context.agentCards().get(0));
        assertThat(result.target().name()).isEqualTo("Order Agent");
        assertThat(result.target().skills()).extracting(skill -> skill.id()).contains("track-order");
    }

    private static Reranker orderFirstReranker() {
        return new Reranker() {
            @Override
            public Map<String, Double> rerankScores(String query, List<?> documents, Object instruct,
                    Map<String, Object> options) {
                Map<String, Double> result = new LinkedHashMap<>();
                for (Object document : documents) {
                    RetrievalResult candidate = (RetrievalResult) document;
                    double score = candidate.getText().contains("[Target]\nOrder Agent") ? 0.95 : 0.20;
                    result.put(candidate.getChunkId(), score);
                }
                return result;
            }

            @Override
            public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
                throw new UnsupportedOperationException("not used by intent recognition");
            }
        };
    }
}
