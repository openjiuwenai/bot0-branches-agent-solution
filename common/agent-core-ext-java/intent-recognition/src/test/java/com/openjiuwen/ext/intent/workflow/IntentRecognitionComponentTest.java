/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.graph.Executable;
import com.openjiuwen.ext.intent.api.IntentRecognitionReason;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import com.openjiuwen.ext.intent.api.IntentResultEncoders;
import com.openjiuwen.ext.intent.tool.IntentRecognitionTool;
import com.openjiuwen.ext.intent.tool.IntentRecognitionToolConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntentRecognitionComponentTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void createsExecutableAndReturnsFieldAddressableMap() {
        IntentRecognitionComponent<String> component = new IntentRecognitionComponent<>(recognizer(), encoder());

        Executable<?, ?> executable = component.toExecutable();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ((IntentRecognitionExecutable<String>) executable)
                .invoke(Map.of("utterance", "find order"), null, null);

        assertThat(result).containsEntry("matched", true).containsEntry("target", "order-agent").containsEntry("reason",
                "MATCHED");
    }

    @Test
    void mapsMalformedInputsToEmptyInput() {
        IntentRecognitionExecutable<String> executable = new IntentRecognitionExecutable<>(recognizer(), encoder());

        @SuppressWarnings("unchecked")
        Map<String, Object> missing = (Map<String, Object>) executable.invoke(Map.of(), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrongType = (Map<String, Object>) executable.invoke(Map.of("utterance", 42), null, null);

        assertThat(missing).containsEntry("matched", false).containsEntry("reason", "EMPTY_INPUT");
        assertThat(wrongType).containsEntry("matched", false).containsEntry("reason", "EMPTY_INPUT");
    }

    @Test
    void producesExactlyTheSameEnvelopeAsAgentTool() {
        IntentRecognizer<String> recognizer = recognizer();
        IntentResultEncoder<String> encoder = encoder();
        IntentRecognitionTool<String> tool = new IntentRecognitionTool<>(recognizer, encoder,
                new IntentRecognitionToolConfig("intent-workflow-parity", null));
        IntentRecognitionExecutable<String> executable = new IntentRecognitionExecutable<>(recognizer, encoder);

        Object toolResult = tool.invoke(Map.of("utterance", "find order"), Map.of());
        Object workflowResult = executable.invoke(Map.of("utterance", "find order"), null, null);

        assertThat(workflowResult)
                .isEqualTo(IntentResultEncoders.toMap((com.fasterxml.jackson.databind.JsonNode) toolResult));
    }

    private static IntentRecognizer<String> recognizer() {
        return new IntentRecognizer<>() {
            @Override
            public IntentRecognitionResult<String> recognize(String utterance) {
                if (utterance == null || utterance.isBlank()) {
                    return IntentRecognitionResult.fallback(IntentRecognitionReason.EMPTY_INPUT);
                }
                return IntentRecognitionResult.matched("order-agent");
            }

            @Override
            public int maxUtteranceLength() {
                return 4096;
            }
        };
    }

    private static IntentResultEncoder<String> encoder() {
        return result -> {
            var envelope = OBJECT_MAPPER.createObjectNode();
            envelope.put("matched", result.matched());
            if (result.target() == null) {
                envelope.putNull("target");
            } else {
                envelope.put("target", result.target());
            }
            envelope.put("reason", result.reason().name());
            return envelope;
        };
    }
}
