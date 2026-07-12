/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.reranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntentRecognizerConfigTest {
    @Test
    void appliesDocumentedSafetyDefaults() {
        IntentRecognizerConfig config = IntentRecognizerConfig.builder().scoreThreshold(0.6).marginThreshold(0.1)
                .candidateFormatVersion("a2a-v1").modelVersion("gte-v1").build();

        assertThat(config.maxUtteranceLength()).isEqualTo(4096);
        assertThat(config.maxTargets()).isEqualTo(100);
        assertThat(config.maxCandidates()).isEqualTo(1000);
        assertThat(config.maxFieldLength()).isEqualTo(4096);
        assertThat(config.maxCandidateLength()).isEqualTo(16384);
        assertThat(config.maxTagsPerCandidate()).isEqualTo(32);
        assertThat(config.maxExamplesPerCandidate()).isEqualTo(16);
        assertThat(config.maxBatchSize()).isEqualTo(128);
        assertThat(config.maxConcurrentRecognitions()).isEqualTo(8);
    }

    @Test
    void rejectsInvalidThresholdsAndMissingVersions() {
        assertThatThrownBy(() -> validBuilder().scoreThreshold(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scoreThreshold");
        assertThatThrownBy(() -> validBuilder().marginThreshold(0.0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marginThreshold");
        assertThatThrownBy(() -> validBuilder().candidateFormatVersion(" ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidateFormatVersion");
        assertThatThrownBy(() -> validBuilder().modelVersion(null).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelVersion");
    }

    @Test
    void rejectsEveryNonPositiveLimit() {
        assertThatThrownBy(() -> validBuilder().maxUtteranceLength(0).build())
                .hasMessageContaining("maxUtteranceLength");
        assertThatThrownBy(() -> validBuilder().maxTargets(0).build()).hasMessageContaining("maxTargets");
        assertThatThrownBy(() -> validBuilder().maxCandidates(0).build()).hasMessageContaining("maxCandidates");
        assertThatThrownBy(() -> validBuilder().maxFieldLength(0).build()).hasMessageContaining("maxFieldLength");
        assertThatThrownBy(() -> validBuilder().maxCandidateLength(0).build())
                .hasMessageContaining("maxCandidateLength");
        assertThatThrownBy(() -> validBuilder().maxTagsPerCandidate(0).build())
                .hasMessageContaining("maxTagsPerCandidate");
        assertThatThrownBy(() -> validBuilder().maxExamplesPerCandidate(0).build())
                .hasMessageContaining("maxExamplesPerCandidate");
        assertThatThrownBy(() -> validBuilder().maxBatchSize(0).build()).hasMessageContaining("maxBatchSize");
        assertThatThrownBy(() -> validBuilder().maxConcurrentRecognitions(0).build())
                .hasMessageContaining("maxConcurrentRecognitions");
    }

    private static IntentRecognizerConfig.Builder validBuilder() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.6).marginThreshold(0.1)
                .candidateFormatVersion("a2a-v1").modelVersion("gte-v1");
    }
}
