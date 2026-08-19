/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.api.IntentExecutionContext;
import com.openjiuwen.agents.intent.exception.IntentMatchException;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogSnapshot;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.NoIntentResultArguments;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Tests reranker-backed intent selection. */
class RerankerIntentMatcherTest {
    @Test
    void appliesThresholdAndBreaksScoreTiesByIntentId() {
        IntentDefinition beta = intent("beta");
        IntentDefinition alpha = intent("alpha");
        IntentExecutionContext context = context(List.of(beta, alpha), 0.65D);
        Reranker reranker = (query, candidates, topK) -> List.of(scored(candidates.get(0), 0.8D),
                scored(candidates.get(1), 0.8D));

        assertThat(new RerankerIntentMatcher(reranker).match(context)).contains(alpha);

        Reranker belowThreshold = (query, candidates, topK) -> List.of(scored(candidates.get(0), 0.64D));
        assertThat(new RerankerIntentMatcher(belowThreshold).match(context)).isEmpty();
    }

    @Test
    void rejectsEmptyUnknownAndNonFiniteRerankerResults() {
        IntentExecutionContext context = context(List.of(intent("known")), 0.65D);
        assertThatThrownBy(() -> new RerankerIntentMatcher((query, candidates, topK) -> List.of()).match(context))
                .isInstanceOf(IntentMatchException.class);
        assertThatThrownBy(() -> new RerankerIntentMatcher((query, candidates, topK) -> List
                .of(new RetrievalResult("unknown", 0.9D, Map.of(), "unknown", "unknown"))).match(context))
                .isInstanceOf(IntentMatchException.class);
        assertThatThrownBy(() -> new RerankerIntentMatcher((query, candidates, topK) -> List
                .of(new RetrievalResult("known", Double.NaN, Map.of(), "known", "known"))).match(context))
                .isInstanceOf(IntentMatchException.class);
    }

    @Test
    void doesNotCallRerankerForEmptyCatalog() {
        Reranker reranker = (query, candidates, topK) -> {
            throw new AssertionError("reranker must not be called");
        };
        assertThat(new RerankerIntentMatcher(reranker).match(context(List.of(), 0.65D))).isEmpty();
    }

    private static IntentExecutionContext context(List<IntentDefinition> intents, double threshold) {
        IntentSuiteConfig config = IntentSuiteConfig.builder().matchThreshold(threshold).build();
        return createContext(config, new IntentCatalogSnapshot(1L, new InitializedIntents(intents, null)));
    }

    private static IntentExecutionContext createContext(IntentSuiteConfig config, IntentCatalogSnapshot snapshot) {
        try {
            var method = IntentExecutionContext.class.getDeclaredMethod("create", IntentSuiteConfig.class,
                    IntentCatalogSnapshot.class, Map.class, Map.class);
            method.setAccessible(true);
            Object result = method.invoke(null, config, snapshot, Map.of("semantic", "query"), Map.of());
            if (result instanceof IntentExecutionContext context) {
                return context;
            }
            throw new AssertionError("create returned an unexpected type");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static IntentDefinition intent(String id) {
        return new IntentDefinition(id, id + " description", context -> new ReturnAction(id),
                NoIntentResultArguments.INSTANCE);
    }

    private static RetrievalResult scored(RetrievalResult candidate, double score) {
        return new RetrievalResult(candidate.getText(), score, candidate.getMetadata(), candidate.getDocId(),
                candidate.getChunkId());
    }
}
