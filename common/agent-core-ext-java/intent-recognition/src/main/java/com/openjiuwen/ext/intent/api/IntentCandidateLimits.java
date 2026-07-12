/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

/** Candidate extraction limits shared by the catalog compiler and protocol adapters. */
public record IntentCandidateLimits(int maxFieldLength, int maxCandidateLength, int maxTagsPerCandidate,
        int maxExamplesPerCandidate) {
    public IntentCandidateLimits {
        requirePositive(maxFieldLength, "maxFieldLength");
        requirePositive(maxCandidateLength, "maxCandidateLength");
        requirePositive(maxTagsPerCandidate, "maxTagsPerCandidate");
        requirePositive(maxExamplesPerCandidate, "maxExamplesPerCandidate");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
