/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Converts a typed recognition result into the shared external JSON envelope. */
@FunctionalInterface
public interface IntentResultEncoder<T> {
    JsonNode encode(IntentRecognitionResult<T> result);
}
