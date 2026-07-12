/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentTargetAdapter;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentCatalogCompilerTest {
    @Test
    void compilesDefensiveSnapshotsAndOrderIndependentCatalogHash() {
        MutableTarget alpha = target("alpha", "a-2", "second", "a-1", "first");
        MutableTarget beta = target("beta", "b-1", "third");
        IntentCatalogCompiler<MutableTarget> compiler = new IntentCatalogCompiler<>(config());

        IntentCatalog<MutableTarget> first = compiler.compile(List.of(alpha, beta), new MutableTargetAdapter());
        IntentCatalog<MutableTarget> reordered = compiler.compile(List.of(beta, alpha), new MutableTargetAdapter());
        alpha.documents().set(0, new MutableDocument("changed", "changed"));

        assertThat(first.targets().get(0).documents().get(0).text()).isEqualTo("second");
        assertThat(first.candidates()).extracting(IntentCandidate::candidateId).containsExactly("alpha:a-1",
                "alpha:a-2", "beta:b-1");
        assertThat(first.catalogHash()).isEqualTo(reordered.catalogHash()).hasSize(64);
        assertThat(first.candidateFormatVersion()).isEqualTo("test-v1");
        assertThatThrownBy(() -> first.targets().add(alpha)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.targetIndexByCandidateId().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateTargetKeysAndCandidateIds() {
        IntentCatalogCompiler<MutableTarget> compiler = new IntentCatalogCompiler<>(config());

        assertThatThrownBy(() -> compiler.compile(List.of(target("same", "a", "one"), target("same", "b", "two")),
                new MutableTargetAdapter())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate targetKey").hasMessageContaining("same");

        MutableTarget duplicateCandidates = target("one", "same", "one", "same", "two");
        assertThatThrownBy(() -> compiler.compile(List.of(duplicateCandidates), new MutableTargetAdapter()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate candidateId")
                .hasMessageContaining("one:same");
    }

    @Test
    void rejectsCatalogAndCandidateLimits() {
        IntentRecognizerConfig limited = baseConfig().maxTargets(1).maxCandidates(1).maxCandidateLength(3).build();
        IntentCatalogCompiler<MutableTarget> compiler = new IntentCatalogCompiler<>(limited);

        assertThatThrownBy(() -> compiler.compile(List.of(target("one", "a", "one"), target("two", "b", "two")),
                new MutableTargetAdapter())).hasMessageContaining("maxTargets");
        assertThatThrownBy(
                () -> compiler.compile(List.of(target("one", "a", "one", "b", "two")), new MutableTargetAdapter()))
                .hasMessageContaining("maxCandidates");
        assertThatThrownBy(() -> compiler.compile(List.of(target("one", "a", "four")), new MutableTargetAdapter()))
                .hasMessageContaining("maxCandidateLength");
    }

    @Test
    void rejectsAdapterOutputThatDoesNotBelongToCurrentTarget() {
        IntentTargetAdapter<MutableTarget> invalid = new MutableTargetAdapter() {
            @Override
            public List<IntentCandidate> candidates(int targetIndex, MutableTarget target) {
                return List.of(new IntentCandidate(targetIndex + 1, target.name() + ":x", "text"));
            }
        };

        assertThatThrownBy(() -> new IntentCatalogCompiler<MutableTarget>(config())
                .compile(List.of(target("one", "x", "text")), invalid)).hasMessageContaining("targetIndex");
    }

    private static IntentRecognizerConfig config() {
        return baseConfig().build();
    }

    private static IntentRecognizerConfig.Builder baseConfig() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("test-v1").modelVersion("model-v1");
    }

    private static MutableTarget target(String name, String... idAndText) {
        List<MutableDocument> documents = new ArrayList<>();
        for (int index = 0; index < idAndText.length; index += 2) {
            documents.add(new MutableDocument(idAndText[index], idAndText[index + 1]));
        }
        return new MutableTarget(name, documents);
    }

    private record MutableDocument(String id, String text) {
    }

    private record MutableTarget(String name, List<MutableDocument> documents) {
    }

    private static class MutableTargetAdapter implements IntentTargetAdapter<MutableTarget> {
        @Override
        public MutableTarget snapshot(MutableTarget target) {
            return new MutableTarget(target.name(), new ArrayList<>(target.documents()));
        }

        @Override
        public String targetKey(MutableTarget target) {
            return target.name();
        }

        @Override
        public List<IntentCandidate> candidates(int targetIndex, MutableTarget target) {
            return target.documents().stream().map(
                    document -> new IntentCandidate(targetIndex, target.name() + ":" + document.id(), document.text()))
                    .toList();
        }
    }
}
