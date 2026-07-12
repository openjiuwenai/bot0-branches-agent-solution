/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.trace;

import com.openjiuwen.ext.intent.api.IntentRecognitionReason;

/** Immutable internal evidence emitted for one recognition attempt. */
public record IntentRecognitionTrace(String targetKey, String candidateId, double topScore, double secondTargetScore,
        IntentRecognitionReason reason, String catalogHash, String modelVersion, String candidateFormatVersion) {
}
