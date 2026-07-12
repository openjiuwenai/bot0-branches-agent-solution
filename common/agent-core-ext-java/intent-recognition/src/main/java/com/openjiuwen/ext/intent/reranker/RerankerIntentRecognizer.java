/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.reranker;

import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentRecognitionReason;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.catalog.IntentCatalog;
import com.openjiuwen.ext.intent.trace.IntentRecognitionTrace;
import com.openjiuwen.ext.intent.trace.IntentTraceListener;
import java.lang.System.Logger.Level;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Full-catalog reranker recognizer with fail-closed acceptance gates. */
public final class RerankerIntentRecognizer<T> implements IntentRecognizer<T> {
    private static final System.Logger LOGGER = System.getLogger(RerankerIntentRecognizer.class.getName());
    private static final String INSTRUCTION = "判断用户请求是否应由给定的候选能力处理。";

    private final IntentCatalog<T> catalog;
    private final Reranker reranker;
    private final IntentRecognizerConfig config;
    private final IntentTraceListener traceListener;
    private final Semaphore scorerPermits;

    public RerankerIntentRecognizer(IntentCatalog<T> catalog, Reranker reranker, IntentRecognizerConfig config,
            IntentTraceListener traceListener) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.reranker = Objects.requireNonNull(reranker, "reranker must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.traceListener = traceListener == null ? IntentTraceListener.NO_OP : traceListener;
        this.scorerPermits = new Semaphore(config.maxConcurrentRecognitions(), true);
    }

    @Override
    public IntentRecognitionResult<T> recognize(String utterance) {
        String normalized = normalize(utterance);
        if (normalized == null || normalized.isEmpty()) {
            return fallback(IntentRecognitionReason.EMPTY_INPUT);
        }
        if (normalized.codePointCount(0, normalized.length()) > config.maxUtteranceLength()) {
            return fallback(IntentRecognitionReason.INPUT_TOO_LONG);
        }
        if (catalog.candidates().isEmpty()) {
            return fallback(IntentRecognitionReason.NO_ELIGIBLE_TARGET);
        }

        ScoringOutcome scoring = scoreAll(normalized);
        if (scoring.reason() != null) {
            return fallback(scoring.reason());
        }
        return aggregate(scoring.scores());
    }

    @Override
    public int maxUtteranceLength() {
        return config.maxUtteranceLength();
    }

    private ScoringOutcome scoreAll(String utterance) {
        boolean acquired = false;
        try {
            scorerPermits.acquire();
            acquired = true;
            Map<String, Double> allScores = new LinkedHashMap<>();
            List<IntentCandidate> candidates = catalog.candidates();
            for (int from = 0; from < candidates.size(); from += config.maxBatchSize()) {
                int to = Math.min(from + config.maxBatchSize(), candidates.size());
                List<IntentCandidate> batch = candidates.subList(from, to);
                List<RetrievalResult> scorerInput = batch.stream()
                        .map(candidate -> new RetrievalResult(candidate.document(), 0.0, Map.of(),
                                candidate.candidateId(), candidate.candidateId()))
                        .toList();
                Map<String, Double> batchScores = reranker.rerankScores(utterance, scorerInput, INSTRUCTION, Map.of());
                if (!validScores(batch, batchScores)) {
                    return new ScoringOutcome(Map.of(), IntentRecognitionReason.INVALID_SCORER_RESPONSE);
                }
                allScores.putAll(batchScores);
            }
            return new ScoringOutcome(Map.copyOf(allScores), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ScoringOutcome(Map.of(), IntentRecognitionReason.SCORER_UNAVAILABLE);
        } catch (RuntimeException exception) {
            return new ScoringOutcome(Map.of(), IntentRecognitionReason.SCORER_UNAVAILABLE);
        } finally {
            if (acquired) {
                scorerPermits.release();
            }
        }
    }

    private boolean validScores(List<IntentCandidate> batch, Map<String, Double> scores) {
        if (scores == null) {
            return false;
        }
        Set<String> expectedIds = new HashSet<>();
        for (IntentCandidate candidate : batch) {
            expectedIds.add(candidate.candidateId());
        }
        if (!scores.keySet().equals(expectedIds)) {
            return false;
        }
        for (Double score : scores.values()) {
            if (score == null || !Double.isFinite(score)) {
                return false;
            }
        }
        return true;
    }

    private IntentRecognitionResult<T> aggregate(Map<String, Double> scores) {
        List<TargetScore> targetScores = new ArrayList<>();
        for (int index = 0; index < catalog.targets().size(); index++) {
            targetScores.add(new TargetScore(index, catalog.targetKeys().get(index), null, Double.NEGATIVE_INFINITY));
        }
        for (IntentCandidate candidate : catalog.candidates()) {
            double score = scores.get(candidate.candidateId());
            TargetScore current = targetScores.get(candidate.targetIndex());
            if (score > current.score() || (score == current.score() && (current.candidateId() == null
                    || candidate.candidateId().compareTo(current.candidateId()) < 0))) {
                targetScores.set(candidate.targetIndex(),
                        new TargetScore(candidate.targetIndex(), current.targetKey(), candidate.candidateId(), score));
            }
        }
        targetScores.removeIf(score -> score.candidateId() == null);
        targetScores.sort((left, right) -> {
            int scoreOrder = Double.compare(right.score(), left.score());
            return scoreOrder != 0 ? scoreOrder : left.targetKey().compareTo(right.targetKey());
        });

        TargetScore top = targetScores.get(0);
        double secondScore = targetScores.size() > 1 ? targetScores.get(1).score() : Double.NaN;
        IntentRecognitionReason reason;
        if (top.score() < config.scoreThreshold()) {
            reason = IntentRecognitionReason.BELOW_SCORE_THRESHOLD;
        } else if (targetScores.size() > 1 && top.score() - secondScore < config.marginThreshold()) {
            reason = IntentRecognitionReason.INSUFFICIENT_MARGIN;
        } else {
            reason = IntentRecognitionReason.MATCHED;
        }
        emit(top, secondScore, reason);
        if (reason == IntentRecognitionReason.MATCHED) {
            return IntentRecognitionResult.matched(catalog.targets().get(top.targetIndex()));
        }
        return IntentRecognitionResult.fallback(reason);
    }

    private IntentRecognitionResult<T> fallback(IntentRecognitionReason reason) {
        emit(null, Double.NaN, reason);
        return IntentRecognitionResult.fallback(reason);
    }

    private void emit(TargetScore top, double secondScore, IntentRecognitionReason reason) {
        IntentRecognitionTrace trace = new IntentRecognitionTrace(top == null ? null : top.targetKey(),
                top == null ? null : top.candidateId(), top == null ? Double.NaN : top.score(), secondScore, reason,
                catalog.catalogHash(), config.modelVersion(), config.candidateFormatVersion());
        try {
            traceListener.onTrace(trace);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "intent trace listener failed: {0}", exception.toString());
        }
    }

    private static String normalize(String utterance) {
        return utterance == null ? null : Normalizer.normalize(utterance, Normalizer.Form.NFC).trim();
    }

    private record ScoringOutcome(Map<String, Double> scores, IntentRecognitionReason reason) {
    }

    private record TargetScore(int targetIndex, String targetKey, String candidateId, double score) {
    }
}
