/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.reranker;

/** Immutable limits and calibrated thresholds for an intent recognizer. */
public record IntentRecognizerConfig(double scoreThreshold, double marginThreshold, int maxUtteranceLength,
        int maxTargets, int maxCandidates, int maxFieldLength, int maxCandidateLength, int maxTagsPerCandidate,
        int maxExamplesPerCandidate, int maxBatchSize, int maxConcurrentRecognitions, String candidateFormatVersion,
        String modelVersion) {

    private static final int DEFAULT_MAX_UTTERANCE_LENGTH = 4096;
    private static final int DEFAULT_MAX_TARGETS = 100;
    private static final int DEFAULT_MAX_CANDIDATES = 1000;
    private static final int DEFAULT_MAX_FIELD_LENGTH = 4096;
    private static final int DEFAULT_MAX_CANDIDATE_LENGTH = 16384;
    private static final int DEFAULT_MAX_TAGS = 32;
    private static final int DEFAULT_MAX_EXAMPLES = 16;
    private static final int DEFAULT_MAX_BATCH_SIZE = 128;
    private static final int DEFAULT_MAX_CONCURRENT_RECOGNITIONS = 8;

    public IntentRecognizerConfig {
        requirePositiveFinite(scoreThreshold, "scoreThreshold");
        requirePositiveFinite(marginThreshold, "marginThreshold");
        requirePositive(maxUtteranceLength, "maxUtteranceLength");
        requirePositive(maxTargets, "maxTargets");
        requirePositive(maxCandidates, "maxCandidates");
        requirePositive(maxFieldLength, "maxFieldLength");
        requirePositive(maxCandidateLength, "maxCandidateLength");
        requirePositive(maxTagsPerCandidate, "maxTagsPerCandidate");
        requirePositive(maxExamplesPerCandidate, "maxExamplesPerCandidate");
        requirePositive(maxBatchSize, "maxBatchSize");
        requirePositive(maxConcurrentRecognitions, "maxConcurrentRecognitions");
        candidateFormatVersion = requireText(candidateFormatVersion, "candidateFormatVersion");
        modelVersion = requireText(modelVersion, "modelVersion");
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    /** Builder retaining safe capacity defaults while requiring calibrated thresholds. */
    public static final class Builder {
        private double scoreThreshold = Double.NaN;
        private double marginThreshold = Double.NaN;
        private int maxUtteranceLength = DEFAULT_MAX_UTTERANCE_LENGTH;
        private int maxTargets = DEFAULT_MAX_TARGETS;
        private int maxCandidates = DEFAULT_MAX_CANDIDATES;
        private int maxFieldLength = DEFAULT_MAX_FIELD_LENGTH;
        private int maxCandidateLength = DEFAULT_MAX_CANDIDATE_LENGTH;
        private int maxTagsPerCandidate = DEFAULT_MAX_TAGS;
        private int maxExamplesPerCandidate = DEFAULT_MAX_EXAMPLES;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private int maxConcurrentRecognitions = DEFAULT_MAX_CONCURRENT_RECOGNITIONS;
        private String candidateFormatVersion;
        private String modelVersion;

        private Builder() {
        }

        public Builder scoreThreshold(double value) {
            scoreThreshold = value;
            return this;
        }

        public Builder marginThreshold(double value) {
            marginThreshold = value;
            return this;
        }

        public Builder maxUtteranceLength(int value) {
            maxUtteranceLength = value;
            return this;
        }

        public Builder maxTargets(int value) {
            maxTargets = value;
            return this;
        }

        public Builder maxCandidates(int value) {
            maxCandidates = value;
            return this;
        }

        public Builder maxFieldLength(int value) {
            maxFieldLength = value;
            return this;
        }

        public Builder maxCandidateLength(int value) {
            maxCandidateLength = value;
            return this;
        }

        public Builder maxTagsPerCandidate(int value) {
            maxTagsPerCandidate = value;
            return this;
        }

        public Builder maxExamplesPerCandidate(int value) {
            maxExamplesPerCandidate = value;
            return this;
        }

        public Builder maxBatchSize(int value) {
            maxBatchSize = value;
            return this;
        }

        public Builder maxConcurrentRecognitions(int value) {
            maxConcurrentRecognitions = value;
            return this;
        }

        public Builder candidateFormatVersion(String value) {
            candidateFormatVersion = value;
            return this;
        }

        public Builder modelVersion(String value) {
            modelVersion = value;
            return this;
        }

        public IntentRecognizerConfig build() {
            return new IntentRecognizerConfig(scoreThreshold, marginThreshold, maxUtteranceLength, maxTargets,
                    maxCandidates, maxFieldLength, maxCandidateLength, maxTagsPerCandidate, maxExamplesPerCandidate,
                    maxBatchSize, maxConcurrentRecognitions, candidateFormatVersion, modelVersion);
        }
    }
}
