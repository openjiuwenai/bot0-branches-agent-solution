/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.tool;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import com.openjiuwen.ext.intent.api.IntentResultEncoders;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Agent Tool adapter for ReAct Agent and DeepAgent. */
public final class IntentRecognitionTool<T> extends Tool {
    private static final String DESCRIPTION = "Match a user request to one configured downstream target.";

    private final IntentRecognizer<T> recognizer;
    private final IntentResultEncoder<T> encoder;

    public IntentRecognitionTool(IntentRecognizer<T> recognizer, IntentResultEncoder<T> encoder,
            IntentRecognitionToolConfig config) {
        super(createCard(recognizer, config));
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer must not be null");
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
    }

    @Override
    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
        String utterance = extractUtterance(inputs);
        return IntentResultEncoders.encodeOrFallback(encoder, recognizer.recognize(utterance));
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
        return List.<Object>of(invoke(inputs, kwargs)).iterator();
    }

    private static String extractUtterance(Map<String, Object> inputs) {
        if (inputs == null) {
            return null;
        }
        Object value = inputs.get("utterance");
        return value instanceof String utterance ? utterance : null;
    }

    private static ToolCard createCard(IntentRecognizer<?> recognizer, IntentRecognitionToolConfig config) {
        Objects.requireNonNull(recognizer, "recognizer must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Map<String, Object> utterance = new LinkedHashMap<>();
        utterance.put("type", "string");
        utterance.put("description", "The user request to match.");
        utterance.put("maxLength", recognizer.maxUtteranceLength());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("utterance", Collections.unmodifiableMap(utterance));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.of("utterance"));
        schema.put("additionalProperties", false);

        return new FrozenToolCard(config.toolId(), config.toolName(), DESCRIPTION, Collections.unmodifiableMap(schema),
                Map.of());
    }

    private static final class FrozenToolCard extends ToolCard {
        private FrozenToolCard(String id, String name, String description, Map<String, Object> inputParams,
                Map<String, Object> properties) {
            super.setId(id);
            super.setName(name);
            super.setDescription(description);
            super.setInputParams(inputParams);
            super.setProperties(properties);
        }

        @Override
        public void setId(String id) {
            throw immutableCard();
        }

        @Override
        public void setName(String name) {
            throw immutableCard();
        }

        @Override
        public void setDescription(String description) {
            throw immutableCard();
        }

        @Override
        public void setInputParams(Map<String, Object> inputParams) {
            throw immutableCard();
        }

        @Override
        public void setProperties(Map<String, Object> properties) {
            throw immutableCard();
        }

        private static UnsupportedOperationException immutableCard() {
            return new UnsupportedOperationException("intent recognition ToolCard is immutable");
        }
    }
}
