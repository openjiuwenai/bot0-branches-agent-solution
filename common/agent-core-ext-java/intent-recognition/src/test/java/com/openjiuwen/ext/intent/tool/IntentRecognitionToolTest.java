/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.ext.intent.api.IntentRecognitionReason;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntentRecognitionToolTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void exposesStableNameUniqueIdAndImmutableDynamicSchema() {
        IntentRecognitionTool<String> tool = new IntentRecognitionTool<>(recognizer(123), encoder(),
                new IntentRecognitionToolConfig("intent-order-7f2c", null));

        assertThat(tool.getCard().getId()).isEqualTo("intent-order-7f2c");
        assertThat(tool.getCard().getName()).isEqualTo("intent_recognition");
        assertThat(tool.getCard().getDescription()).isNotBlank();
        assertThat(tool.getCard().getInputParams()).containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getCard().getInputParams().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> utterance = (Map<String, Object>) properties.get("utterance");
        assertThat(utterance).containsEntry("type", "string").containsEntry("maxLength", 123);
        assertThatThrownBy(() -> utterance.put("maxLength", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().getInputParams().put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().setId("replacement")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().setName("replacement"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().setDescription("replacement"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().setInputParams(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tool.getCard().setProperties(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invokesWithFixedBusinessInputAndIgnoresOptionalFrameworkKwargs() {
        AtomicReference<String> received = new AtomicReference<>();
        IntentRecognizer<String> recognizer = new IntentRecognizer<>() {
            @Override
            public IntentRecognitionResult<String> recognize(String utterance) {
                received.set(utterance);
                return IntentRecognitionResult.matched("target");
            }

            @Override
            public int maxUtteranceLength() {
                return 4096;
            }
        };
        IntentRecognitionTool<String> tool = new IntentRecognitionTool<>(recognizer, encoder(),
                new IntentRecognitionToolConfig("intent-1", "route_intent"));

        JsonNode result = (JsonNode) tool.invoke(Map.of("utterance", "find order"), Map.of("session", "ignored"));
        Iterator<Object> streamed = tool.stream(Map.of("utterance", "find order"), null);

        assertThat(received.get()).isEqualTo("find order");
        assertThat(result.path("matched").asBoolean()).isTrue();
        assertThat(streamed).toIterable().containsExactly(result);
        assertThat(tool.getCard().getName()).isEqualTo("route_intent");
    }

    @Test
    void mapsInvalidUtteranceAndEncoderFailureToFallback() {
        IntentRecognitionTool<String> ordinary = new IntentRecognitionTool<>(recognizer(4096), encoder(),
                new IntentRecognitionToolConfig("intent-1", null));
        IntentRecognitionTool<String> brokenEncoder = new IntentRecognitionTool<>(recognizer(4096), result -> {
            throw new IllegalStateException("broken");
        }, new IntentRecognitionToolConfig("intent-2", null));

        JsonNode missing = (JsonNode) ordinary.invoke(Map.of(), Map.of());
        JsonNode wrongType = (JsonNode) ordinary.invoke(Map.of("utterance", 42), Map.of());
        JsonNode failedEncoding = (JsonNode) brokenEncoder.invoke(Map.of("utterance", "query"), Map.of());

        assertThat(missing.path("reason").asText()).isEqualTo("EMPTY_INPUT");
        assertThat(wrongType.path("reason").asText()).isEqualTo("EMPTY_INPUT");
        assertThat(failedEncoding.toString())
                .isEqualTo("{\"matched\":false,\"target\":null,\"reason\":\"RESULT_ENCODING_FAILED\"}");
    }

    @Test
    void rejectsBlankToolIds() {
        assertThatThrownBy(() -> new IntentRecognitionTool<>(recognizer(10), encoder(),
                new IntentRecognitionToolConfig(" ", null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolId");
    }

    private static IntentRecognizer<String> recognizer(int maxLength) {
        return new IntentRecognizer<>() {
            @Override
            public IntentRecognitionResult<String> recognize(String utterance) {
                if (utterance == null || utterance.isBlank()) {
                    return IntentRecognitionResult.fallback(IntentRecognitionReason.EMPTY_INPUT);
                }
                return IntentRecognitionResult.matched("target");
            }

            @Override
            public int maxUtteranceLength() {
                return maxLength;
            }
        };
    }

    private static IntentResultEncoder<String> encoder() {
        return result -> {
            var node = OBJECT_MAPPER.createObjectNode();
            node.put("matched", result.matched());
            if (result.target() == null) {
                node.putNull("target");
            } else {
                node.put("target", result.target());
            }
            node.put("reason", result.reason().name());
            return node;
        };
    }
}
