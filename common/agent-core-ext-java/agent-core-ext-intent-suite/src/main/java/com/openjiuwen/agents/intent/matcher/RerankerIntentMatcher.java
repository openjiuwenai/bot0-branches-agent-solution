/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.matcher;

import com.openjiuwen.agents.intent.api.IntentExecutionContext;
import com.openjiuwen.agents.intent.exception.IntentMatchException;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.spi.IntentMatcher;
import com.openjiuwen.core.common.exception.BaseError;
import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default matcher backed by the AgentCore reranker SPI.
 *
 * @since 0.1.0
 */
public final class RerankerIntentMatcher implements IntentMatcher {
    private static final Logger log = LoggerFactory.getLogger(RerankerIntentMatcher.class);

    private final Reranker reranker;

    /**
     * Creates a matcher.
     *
     * @param reranker AgentCore reranker
     */
    public RerankerIntentMatcher(Reranker reranker) {
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    @Override
    public Optional<IntentDefinition> match(IntentExecutionContext context) {
        List<IntentDefinition> intents = context.catalogSnapshot().initializedIntents().matchableIntents();
        if (intents.isEmpty()) {
            log.info("Intent matcher skipped because catalogVersion={} has no matchable intents",
                    context.catalogSnapshot().version());
            return Optional.empty();
        }
        Map<String, IntentDefinition> intentsById = indexIntents(intents);
        List<RetrievalResult> candidates = intents.stream()
                .map(intent -> new RetrievalResult(intent.description(), 0.0D, Map.of(), intent.id(), intent.id()))
                .toList();
        List<RetrievalResult> results;
        try {
            results = reranker.rerank(context.routingSemantic(), candidates, candidates.size());
        } catch (BaseError | IllegalArgumentException exception) {
            throw new IntentMatchException("reranker execution failed", exception);
        }
        RankedIntent top = selectTop(results, intentsById);
        double threshold = context.config().matchThreshold();
        boolean isMatched = top.score() >= threshold;
        log.info(
                "Intent matcher decision catalogVersion={} candidateCount={} topIntentId={} score={} threshold={} "
                        + "matched={}",
                context.catalogSnapshot().version(), intents.size(), top.intent().id(), top.score(), threshold,
                isMatched);
        if (!isMatched) {
            return Optional.empty();
        }
        return Optional.of(top.intent());
    }

    private static Map<String, IntentDefinition> indexIntents(List<IntentDefinition> intents) {
        Map<String, IntentDefinition> indexed = new LinkedHashMap<>();
        for (IntentDefinition intent : intents) {
            if (indexed.put(intent.id(), intent) != null) {
                throw new IntentMatchException("duplicate initialized intent id: " + intent.id());
            }
        }
        return indexed;
    }

    private static RankedIntent selectTop(List<RetrievalResult> results, Map<String, IntentDefinition> intentsById) {
        if (results == null || results.isEmpty()) {
            throw new IntentMatchException("reranker returned no results");
        }
        List<RankedIntent> ranked = results
                .stream().map(result -> mapResult(result, intentsById)).sorted(Comparator
                        .comparingDouble(RankedIntent::score).reversed().thenComparing(value -> value.intent().id()))
                .toList();
        return ranked.get(0);
    }

    private static RankedIntent mapResult(RetrievalResult result, Map<String, IntentDefinition> intentsById) {
        if (result == null || !Double.isFinite(result.getScore())) {
            throw new IntentMatchException("reranker returned an invalid score");
        }
        String id = result.getDocId() == null ? result.getChunkId() : result.getDocId();
        IntentDefinition intent = intentsById.get(id);
        if (intent == null) {
            throw new IntentMatchException("reranker result does not map to a catalog intent: " + id);
        }
        return new RankedIntent(intent, result.getScore());
    }

    private record RankedIntent(IntentDefinition intent, double score) {
    }
}
