/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.reranker;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentRecognitionReason;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentTargetAdapter;
import com.openjiuwen.ext.intent.catalog.IntentCatalog;
import com.openjiuwen.ext.intent.catalog.IntentCatalogCompiler;
import com.openjiuwen.ext.intent.trace.IntentRecognitionTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RerankerIntentRecognizerTest {
    @Test
    void validatesNormalizedInputByUnicodeCodePointWithoutCallingScorer() {
        IntentRecognizerConfig config = configBuilder().maxUtteranceLength(1).build();
        CapturingReranker reranker = new CapturingReranker(ids -> scores(ids, 0.9));
        RerankerIntentRecognizer<String> recognizer = recognizer(catalog(List.of(target("one", "a")), config), reranker,
                config);

        assertThat(recognizer.recognize(null).reason()).isEqualTo(IntentRecognitionReason.EMPTY_INPUT);
        assertThat(recognizer.recognize("  ").reason()).isEqualTo(IntentRecognitionReason.EMPTY_INPUT);
        assertThat(recognizer.recognize("ab").reason()).isEqualTo(IntentRecognitionReason.INPUT_TOO_LONG);
        assertThat(reranker.batches).isEmpty();

        assertThat(recognizer.recognize("\uD83D\uDE00").matched()).isTrue();
        assertThat(reranker.queries).containsExactly("\uD83D\uDE00");
    }

    @Test
    void scoresStableBatchesUsingCandidateIdsAsChunkIds() {
        IntentRecognizerConfig config = configBuilder().maxBatchSize(2).build();
        CapturingReranker reranker = new CapturingReranker(ids -> {
            Map<String, Double> values = Map.of("one:a", 0.9, "one:b", 0.8, "two:c", 0.6);
            Map<String, Double> batch = new LinkedHashMap<>();
            ids.forEach(id -> batch.put(id, values.get(id)));
            return batch;
        });
        IntentCatalog<String> catalog = catalog(List.of(target("two", "c"), target("one", "b", "a")), config);
        RerankerIntentRecognizer<String> recognizer = recognizer(catalog, reranker, config);

        IntentRecognitionResult<String> result = recognizer.recognize("query");

        assertThat(result).isEqualTo(new IntentRecognitionResult<>(true, "one", IntentRecognitionReason.MATCHED));
        assertThat(reranker.batches).containsExactly(List.of("one:a", "one:b"), List.of("two:c"));
    }

    @Test
    void rejectsMissingUnknownAndNonFiniteScores() {
        IntentRecognizerConfig config = configBuilder().build();
        IntentCatalog<String> catalog = catalog(List.of(target("one", "a")), config);

        assertThat(recognizer(catalog, new CapturingReranker(ids -> Map.of()), config).recognize("q").reason())
                .isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
        assertThat(recognizer(catalog, new CapturingReranker(ids -> Map.of("unknown", 0.9)), config).recognize("q")
                .reason()).isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
        assertThat(recognizer(catalog, new CapturingReranker(ids -> Map.of(ids.get(0), Double.NaN)), config)
                .recognize("q").reason()).isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
        assertThat(recognizer(catalog, new CapturingReranker(ids -> {
            Map<String, Double> values = new LinkedHashMap<>();
            values.put(ids.get(0), null);
            return values;
        }), config).recognize("q").reason()).isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
        assertThat(
                recognizer(catalog, new CapturingReranker(ids -> Map.of(ids.get(0), Double.POSITIVE_INFINITY)), config)
                        .recognize("q").reason())
                .isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
        assertThat(
                recognizer(catalog, new CapturingReranker(ids -> Map.of(ids.get(0), Double.NEGATIVE_INFINITY)), config)
                        .recognize("q").reason())
                .isEqualTo(IntentRecognitionReason.INVALID_SCORER_RESPONSE);
    }

    @Test
    void discardsAllScoresWhenAnyBatchFails() {
        IntentRecognizerConfig config = configBuilder().maxBatchSize(1).build();
        AtomicInteger calls = new AtomicInteger();
        CapturingReranker reranker = new CapturingReranker(ids -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("service down");
            }
            return scores(ids, 0.99);
        });
        RerankerIntentRecognizer<String> recognizer = recognizer(
                catalog(List.of(target("one", "a"), target("two", "b")), config), reranker, config);

        IntentRecognitionResult<String> result = recognizer.recognize("query");

        assertThat(result.matched()).isFalse();
        assertThat(result.target()).isNull();
        assertThat(result.reason()).isEqualTo(IntentRecognitionReason.SCORER_UNAVAILABLE);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aggregatesByTargetThenAppliesScoreAndCrossTargetMargin() {
        IntentRecognizerConfig config = configBuilder().scoreThreshold(0.5).marginThreshold(0.1).build();
        IntentCatalog<String> catalog = catalog(List.of(target("one", "a", "b"), target("two", "c")), config);

        IntentRecognitionResult<String> matched = recognizer(catalog,
                new CapturingReranker(ids -> Map.of("one:a", 0.90, "one:b", 0.89, "two:c", 0.70)), config)
                .recognize("q");
        IntentRecognitionResult<String> ambiguous = recognizer(catalog,
                new CapturingReranker(ids -> Map.of("one:a", 0.90, "one:b", 0.20, "two:c", 0.85)), config)
                .recognize("q");
        IntentRecognitionResult<String> below = recognizer(catalog,
                new CapturingReranker(ids -> Map.of("one:a", 0.40, "one:b", 0.30, "two:c", 0.20)), config)
                .recognize("q");

        assertThat(matched).isEqualTo(new IntentRecognitionResult<>(true, "one", IntentRecognitionReason.MATCHED));
        assertThat(ambiguous.reason()).isEqualTo(IntentRecognitionReason.INSUFFICIENT_MARGIN);
        assertThat(below.reason()).isEqualTo(IntentRecognitionReason.BELOW_SCORE_THRESHOLD);
    }

    @Test
    void treatsSingleEligibleTargetMarginAsPassedAndReportsEmptyCatalog() {
        IntentRecognizerConfig config = configBuilder().build();
        IntentRecognitionResult<String> single = recognizer(catalog(List.of(target("one", "a")), config),
                new CapturingReranker(ids -> scores(ids, 0.8)), config).recognize("q");
        IntentRecognitionResult<String> empty = recognizer(catalog(List.of(), config),
                new CapturingReranker(ids -> Map.of()), config).recognize("q");

        assertThat(single.matched()).isTrue();
        assertThat(empty.reason()).isEqualTo(IntentRecognitionReason.NO_ELIGIBLE_TARGET);
    }

    @Test
    void acceptsScoresExactlyOnBothGateBoundaries() {
        IntentRecognizerConfig config = configBuilder().scoreThreshold(0.5).marginThreshold(0.25).build();
        IntentCatalog<String> catalog = catalog(List.of(target("one", "a"), target("two", "b")), config);

        IntentRecognitionResult<String> result = recognizer(catalog,
                new CapturingReranker(ids -> Map.of("one:a", 0.75, "two:b", 0.50)), config).recognize("q");

        assertThat(result).isEqualTo(IntentRecognitionResult.matched("one"));
    }

    @Test
    void listenerFailureDoesNotChangeRecognitionResult() {
        IntentRecognizerConfig config = configBuilder().build();
        List<IntentRecognitionTrace> traces = new ArrayList<>();
        RerankerIntentRecognizer<String> recognizer = new RerankerIntentRecognizer<>(
                catalog(List.of(target("one", "a"), target("two", "b")), config),
                new CapturingReranker(ids -> Map.of("one:a", 0.9, "two:b", 0.4)), config, trace -> {
                    traces.add(trace);
                    throw new IllegalStateException("listener failure");
                });

        IntentRecognitionResult<String> result = recognizer.recognize("q");

        assertThat(result.matched()).isTrue();
        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.targetKey()).isEqualTo("one");
            assertThat(trace.candidateId()).isEqualTo("one:a");
            assertThat(trace.catalogHash()).hasSize(64);
            assertThat(trace.modelVersion()).isEqualTo("model-v1");
        });
    }

    @Test
    void limitsConcurrentRecognitionsAcrossWholeBatchSequence() throws Exception {
        IntentRecognizerConfig config = configBuilder().maxConcurrentRecognitions(1).build();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CapturingReranker reranker = new CapturingReranker(ids -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstEntered.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
                return scores(ids, 0.9);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        });
        RerankerIntentRecognizer<String> recognizer = recognizer(catalog(List.of(target("one", "a")), config), reranker,
                config);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IntentRecognitionResult<String>> first = executor.submit(() -> recognizer.recognize("first"));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<IntentRecognitionResult<String>> second = executor.submit(() -> recognizer.recognize("second"));
            Thread.sleep(100);
            assertThat(maxActive).hasValue(1);
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).matched()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).matched()).isTrue();
            assertThat(maxActive).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static RerankerIntentRecognizer<String> recognizer(IntentCatalog<String> catalog, Reranker reranker,
            IntentRecognizerConfig config) {
        return new RerankerIntentRecognizer<>(catalog, reranker, config, trace -> {
        });
    }

    private static IntentCatalog<String> catalog(List<Target> targets, IntentRecognizerConfig config) {
        return new IntentCatalogCompiler<String>(config).compile(targets.stream().map(Target::name).toList(),
                new IntentTargetAdapter<>() {
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
                        Target source = targets.stream().filter(item -> item.name().equals(target)).findFirst()
                                .orElseThrow();
                        return source.ids().stream()
                                .map(id -> new IntentCandidate(targetIndex, target + ":" + id, target + " " + id))
                                .toList();
                    }
                });
    }

    private static Target target(String name, String... ids) {
        return new Target(name, List.of(ids));
    }

    private static IntentRecognizerConfig.Builder configBuilder() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("test-v1").modelVersion("model-v1");
    }

    private static Map<String, Double> scores(List<String> ids, double... values) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            scores.put(ids.get(index), values[Math.min(index, values.length - 1)]);
        }
        return scores;
    }

    private record Target(String name, List<String> ids) {
    }

    private static final class CapturingReranker implements Reranker {
        private final Function<List<String>, Map<String, Double>> scoring;
        private final List<String> queries = new ArrayList<>();
        private final List<List<String>> batches = new ArrayList<>();

        private CapturingReranker(Function<List<String>, Map<String, Double>> scoring) {
            this.scoring = scoring;
        }

        @Override
        public Map<String, Double> rerankScores(String query, List<?> documents, Object instruct,
                Map<String, Object> options) {
            queries.add(query);
            List<String> ids = documents.stream().map(RetrievalResult.class::cast).map(RetrievalResult::getChunkId)
                    .toList();
            batches.add(ids);
            return scoring.apply(ids);
        }

        @Override
        public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
            throw new UnsupportedOperationException();
        }
    }
}
