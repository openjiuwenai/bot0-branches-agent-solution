/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import com.openjiuwen.ext.intent.api.IntentResultEncoders;
import java.util.Map;
import java.util.Objects;

/** Batch Workflow node that exposes the shared intent result as a field-addressable Map. */
public final class IntentRecognitionExecutable<T> extends ComponentExecutable {
    private final IntentRecognizer<T> recognizer;
    private final IntentResultEncoder<T> encoder;

    public IntentRecognitionExecutable(IntentRecognizer<T> recognizer, IntentResultEncoder<T> encoder) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer must not be null");
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
    }

    @Override
    public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
        String utterance = extractUtterance(inputs);
        JsonNode output = IntentResultEncoders.encodeOrFallback(encoder, recognizer.recognize(utterance));
        return IntentResultEncoders.toMap(output);
    }

    private static String extractUtterance(Object inputs) {
        if (!(inputs instanceof Map<?, ?> inputMap)) {
            return null;
        }
        Object value = inputMap.get("utterance");
        return value instanceof String utterance ? utterance : null;
    }
}
