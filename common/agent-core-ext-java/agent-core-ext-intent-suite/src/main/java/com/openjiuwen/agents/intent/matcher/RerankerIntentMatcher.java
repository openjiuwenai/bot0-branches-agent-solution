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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default matcher backed by the AgentCore reranker SPI.
 *
 * @since 0.1.0
 */
public final class RerankerIntentMatcher implements IntentMatcher {
    private static final Logger log = LoggerFactory.getLogger(RerankerIntentMatcher.class);
    private static final int LOGGED_CANDIDATES = 3;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Reranker reranker;
    private final String queryInstruction;

    /**
     * Creates a matcher that sends the routing semantic to the reranker unchanged.
     *
     * @param reranker AgentCore reranker
     */
    public RerankerIntentMatcher(Reranker reranker) {
        this(reranker, "");
    }

    /**
     * Creates a matcher that prepends a task instruction to the reranker query.
     *
     * <p>
     * Relevance rerankers score whether a document answers the query. A bare user utterance is a
     * question that an intent description never answers, which collapses the absolute score even
     * when the ranking stays correct. Prepending a short task instruction restates the query as a
     * routing request that the description does answer, and lifts true-positive scores by roughly
     * an order of magnitude. The instruction is reranker and language specific, so it has no
     * built-in default and must be configured by the deployment.
     *
     * @param reranker AgentCore reranker
     * @param queryInstruction instruction prefix, blank to send the semantic unchanged
     */
    public RerankerIntentMatcher(Reranker reranker, String queryInstruction) {
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.queryInstruction = queryInstruction == null ? "" : queryInstruction.strip();
    }

    /**
     * Returns the reranker query built from the routing semantic.
     *
     * @param semantic routing semantic
     * @return instructed query
     */
    String rerankerQuery(String semantic) {
        return queryInstruction.isEmpty() ? semantic : queryInstruction + semantic;
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
        long startedAt = System.nanoTime();
        try {
            results = reranker.rerank(rerankerQuery(context.routingSemantic()), candidates, candidates.size());
        } catch (BaseError | IllegalArgumentException exception) {
            throw new IntentMatchException("reranker execution failed", exception);
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;
        List<RankedIntent> ranked = rank(results, intentsById);
        RankedIntent top = ranked.get(0);
        double threshold = context.config().matchThreshold();
        boolean isMatched = top.score() >= threshold;
        log.info(
                "Intent matcher decision catalogVersion={} candidateCount={} topIntentId={} score={} threshold={} "
                        + "matched={} rerankerMillis={} topCandidates={}",
                context.catalogSnapshot().version(), intents.size(), top.intent().id(), top.score(), threshold,
                isMatched, elapsedMillis, formatTopCandidates(ranked));
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

    private static List<RankedIntent> rank(List<RetrievalResult> results, Map<String, IntentDefinition> intentsById) {
        if (results == null || results.isEmpty()) {
            throw new IntentMatchException("reranker returned no results");
        }
        return results
                .stream().map(result -> mapResult(result, intentsById)).sorted(Comparator
                        .comparingDouble(RankedIntent::score).reversed().thenComparing(value -> value.intent().id()))
                .toList();
    }

    /**
     * Renders the leading candidates so a wrong route can be diagnosed against the runner-up margin.
     *
     * @param ranked candidates ordered by descending score
     * @return comma separated {@code intentId:score} pairs
     */
    private static String formatTopCandidates(List<RankedIntent> ranked) {
        return ranked.stream().limit(LOGGED_CANDIDATES)
                .map(value -> value.intent().id() + ":" + String.format(Locale.ROOT, "%.4f", value.score()))
                .collect(Collectors.joining(","));
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
