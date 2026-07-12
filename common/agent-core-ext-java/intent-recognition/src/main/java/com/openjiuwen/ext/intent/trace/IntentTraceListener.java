/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.trace;

/** Synchronous observer for recognition traces. */
@FunctionalInterface
public interface IntentTraceListener {
    IntentTraceListener NO_OP = trace -> {
    };

    void onTrace(IntentRecognitionTrace trace);
}
