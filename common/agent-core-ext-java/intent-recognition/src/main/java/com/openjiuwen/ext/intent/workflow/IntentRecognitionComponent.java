/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.workflow;

import com.openjiuwen.core.graph.Executable;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import java.util.Objects;

/** Workflow-composable wrapper for the shared intent recognizer. */
public final class IntentRecognitionComponent<T> implements ComponentComposable {
    private final IntentRecognizer<T> recognizer;
    private final IntentResultEncoder<T> encoder;

    public IntentRecognitionComponent(IntentRecognizer<T> recognizer, IntentResultEncoder<T> encoder) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer must not be null");
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
    }

    @Override
    public Executable<?, ?> toExecutable() {
        return new IntentRecognitionExecutable<>(recognizer, encoder);
    }
}
