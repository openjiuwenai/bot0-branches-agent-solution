/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

/** Stable public outcomes for intent recognition. */
public enum IntentRecognitionReason {
    MATCHED,
    EMPTY_INPUT,
    INPUT_TOO_LONG,
    NO_ELIGIBLE_TARGET,
    BELOW_SCORE_THRESHOLD,
    INSUFFICIENT_MARGIN,
    SCORER_UNAVAILABLE,
    INVALID_SCORER_RESPONSE,
    RESULT_ENCODING_FAILED
}
