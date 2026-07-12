/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

/** Thread-safe protocol-neutral intent recognition API. */
public interface IntentRecognizer<T> {
    IntentRecognitionResult<T> recognize(String utterance);

    int maxUtteranceLength();
}
