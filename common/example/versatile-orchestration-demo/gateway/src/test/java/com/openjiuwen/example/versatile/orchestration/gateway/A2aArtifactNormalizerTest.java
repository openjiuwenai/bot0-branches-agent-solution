/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Covers {@link A2aArtifactNormalizer} with the same JSON-shaped text payloads plan-agent
 * emits (captured from {@code target/sit-logs/edpa-gateway/stdout.log}), now delivered via
 * typed {@link TaskArtifactUpdateEvent} instances built with the SDK's own record builders.
 */
class A2aArtifactNormalizerTest {
    /** llm_output payload (verbatim from a real plan-agent stream). */
    private static final String LLM_OUTPUT_TEXT =
            "{\"type\":\"llm_output\",\"index\":0,"
                    + "\"payload\":{\"result_type\":\"answer\",\"content\":\"\"}}";

    private static final String LLM_USAGE_TEXT =
            "{\"type\":\"llm_usage\",\"index\":0,"
                    + "\"payload\":{\"result_type\":\"answer\","
                    + "\"usage_metadata\":{\"modelName\":\"deepseek-v4-pro\","
                    + "\"inputTokens\":10,\"outputTokens\":20}}}";

    private static final String LLM_REASONING_TEXT =
            "{\"type\":\"llm_reasoning\",\"index\":2,"
                    + "\"payload\":{\"result_type\":\"answer\",\"content\":\"用户\"}}";

    /** versatile-simulated text is already EDPA-shaped — pass it through verbatim. */
    private static final String VERSATILE_TEXT =
            "{\"event\":\"message\","
                    + "\"data\":{\"text\":\"hi\",\"node_type\":\"QA\"}}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void liftsLlmOutputTypeAsEventAndNestsWholeObjectUnderData() throws Exception {
        List<String> out = A2aArtifactNormalizer.normalize(mapper, artifactEvent(LLM_OUTPUT_TEXT));

        assertThat(out).hasSize(1);
        JsonNode envelope = mapper.readTree(out.get(0));
        assertThat(envelope.get("event").asText()).isEqualTo("llm_output");
        // data is the WHOLE text object verbatim — type + index + payload all preserved.
        JsonNode data = envelope.get("data");
        assertThat(data.get("type").asText()).isEqualTo("llm_output");
        assertThat(data.get("index").asInt()).isZero();
        assertThat(data.get("payload").get("result_type").asText()).isEqualTo("answer");
    }

    @Test
    void liftsLlmUsageAndReasoningSimilarly() throws Exception {
        for (String text : List.of(LLM_USAGE_TEXT, LLM_REASONING_TEXT)) {
            JsonNode envelope = mapper.readTree(
                    A2aArtifactNormalizer.normalize(mapper, artifactEvent(text)).get(0));
            String eventType = envelope.get("event").asText();
            assertThat(eventType).isIn("llm_usage", "llm_reasoning");
            assertThat(envelope.get("data").get("type").asText()).isEqualTo(eventType);
        }
        // usage payload's nested usage_metadata survives the nesting.
        JsonNode usageEnvelope = mapper.readTree(
                A2aArtifactNormalizer.normalize(mapper, artifactEvent(LLM_USAGE_TEXT)).get(0));
        assertThat(usageEnvelope.get("data").get("payload").get("usage_metadata").get("modelName").asText())
                .isEqualTo("deepseek-v4-pro");
    }

    @Test
    void passesThroughVersatileAlreadyEdpaShapedText() throws Exception {
        List<String> out = A2aArtifactNormalizer.normalize(mapper, artifactEvent(VERSATILE_TEXT));
        assertThat(out).hasSize(1);
        JsonNode envelope = mapper.readTree(out.get(0));
        // versatile text already had {event,data} → forwarded as-is (no double-wrapping).
        assertThat(envelope.get("event").asText()).isEqualTo("message");
        assertThat(envelope.get("data").get("text").asText()).isEqualTo("hi");
        assertThat(envelope.get("data").get("node_type").asText()).isEqualTo("QA");
    }

    @Test
    void emitsOneEnvelopePerTextPart() throws Exception {
        String textA = "{\"type\":\"llm_output\",\"payload\":{\"content\":\"a\"}}";
        String textB = "{\"type\":\"llm_reasoning\",\"payload\":{\"content\":\"b\"}}";
        List<String> out = A2aArtifactNormalizer.normalize(mapper, artifactEvent(textA, textB));
        assertThat(out).hasSize(2);
        assertThat(mapper.readTree(out.get(0)).get("event").asText()).isEqualTo("llm_output");
        assertThat(mapper.readTree(out.get(1)).get("event").asText()).isEqualTo("llm_reasoning");
    }

    @Test
    void nonJsonTextFallsBackToMessageEnvelopeWithRawText() throws Exception {
        List<String> out = A2aArtifactNormalizer.normalize(mapper, artifactEvent("plain not-json content"));
        assertThat(out).hasSize(1);
        JsonNode envelope = mapper.readTree(out.get(0));
        assertThat(envelope.get("event").asText()).isEqualTo("message");
        assertThat(envelope.get("data").get("text").asText()).isEqualTo("plain not-json content");
    }

    @Test
    void silentlySkipsNonTextParts() throws Exception {
        // DataPart / FilePart never carry the {event,data} EDPA envelope this pipeline forwards —
        // they must be dropped so a mixed artifact still emits the right number of frames.
        Artifact mixed = Artifact.builder()
                .artifactId("a")
                .parts(List.of(
                        new DataPart(Map.of("foo", "bar")),
                        new TextPart(LLM_OUTPUT_TEXT)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("t").contextId("c").artifact(mixed).build();
        List<String> out = A2aArtifactNormalizer.normalize(mapper, event);
        assertThat(out).hasSize(1);
        assertThat(mapper.readTree(out.get(0)).get("event").asText()).isEqualTo("llm_output");
    }

    private static TaskArtifactUpdateEvent artifactEvent(String... texts) {
        List<Part<?>> parts = new ArrayList<>();
        for (String text : texts) {
            parts.add(new TextPart(text));
        }
        Artifact artifact = Artifact.builder()
                .artifactId("a")
                .parts(parts)
                .build();
        return TaskArtifactUpdateEvent.builder()
                .taskId("t")
                .contextId("c")
                .artifact(artifact)
                .build();
    }
}
