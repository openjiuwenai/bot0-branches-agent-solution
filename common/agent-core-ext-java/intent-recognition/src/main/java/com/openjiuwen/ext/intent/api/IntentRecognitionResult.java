/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import java.util.Objects;

/** Public recognition envelope returned by all framework adapters. */
public record IntentRecognitionResult<T>(boolean matched, T target, IntentRecognitionReason reason) {
    public IntentRecognitionResult {
        Objects.requireNonNull(reason, "reason must not be null");
        if (matched && (target == null || reason != IntentRecognitionReason.MATCHED)) {
            throw new IllegalArgumentException("matched results require a target and MATCHED reason");
        }
        if (!matched && (target != null || reason == IntentRecognitionReason.MATCHED)) {
            throw new IllegalArgumentException("fallback results require a null target and non-MATCHED reason");
        }
    }

    public static <T> IntentRecognitionResult<T> matched(T target) {
        return new IntentRecognitionResult<>(true, target, IntentRecognitionReason.MATCHED);
    }

    public static <T> IntentRecognitionResult<T> fallback(IntentRecognitionReason reason) {
        return new IntentRecognitionResult<>(false, null, reason);
    }
}
