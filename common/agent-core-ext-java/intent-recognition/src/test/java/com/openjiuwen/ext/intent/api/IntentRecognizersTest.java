/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntentRecognizersTest {
    @Test
    void buildsConfiguredRecognizerFromCallerOwnedDependencies() {
        IntentRecognizer<String> recognizer = IntentRecognizers.<String>builder().targets(List.of("orders"))
                .targetAdapter(adapter()).reranker(reranker()).config(config()).build();

        assertThat(recognizer.recognize("track order")).isEqualTo(IntentRecognitionResult.matched("orders"));
        assertThat(recognizer.maxUtteranceLength()).isEqualTo(4096);
    }

    @Test
    void failsInitializationWhenRequiredDependencyIsMissing() {
        assertThatThrownBy(() -> IntentRecognizers.<String>builder().targets(List.of("orders")).targetAdapter(adapter())
                .config(config()).build()).isInstanceOf(NullPointerException.class).hasMessageContaining("reranker");
    }

    private static IntentTargetAdapter<String> adapter() {
        return new IntentTargetAdapter<>() {
            @Override
            public String snapshot(String target) {
                return target;
            }

            @Override
            public String targetKey(String target) {
                return target;
            }

            @Override
            public List<IntentCandidate> candidates(int targetIndex, String target) {
                return List.of(new IntentCandidate(targetIndex, target + ":route", "route " + target));
            }
        };
    }

    private static Reranker reranker() {
        return new Reranker() {
            @Override
            public Map<String, Double> rerankScores(String query, List<?> documents, Object instruct,
                    Map<String, Object> options) {
                RetrievalResult result = (RetrievalResult) documents.get(0);
                return Map.of(result.getChunkId(), 0.9);
            }

            @Override
            public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static IntentRecognizerConfig config() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("test-v1").modelVersion("model-v1").build();
    }
}
