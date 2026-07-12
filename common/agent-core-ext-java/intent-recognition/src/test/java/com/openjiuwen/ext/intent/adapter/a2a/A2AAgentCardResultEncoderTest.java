/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.ext.intent.api.IntentRecognitionReason;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentResultEncoders;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCardSignature;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.junit.jupiter.api.Test;

class A2AAgentCardResultEncoderTest {
    @Test
    void encodesEveryNonNullAgentCardComponentUsingProtocolFieldNames() throws Exception {
        AgentCard card = fullCard();
        A2AAgentCardResultEncoder encoder = new A2AAgentCardResultEncoder();

        JsonNode envelope = encoder.encode(IntentRecognitionResult.matched(card));

        assertThat(envelope.path("matched").asBoolean()).isTrue();
        assertThat(envelope.path("reason").asText()).isEqualTo("MATCHED");
        JsonNode target = envelope.path("target");
        for (RecordComponent component : AgentCard.class.getRecordComponents()) {
            if (component.getAccessor().invoke(card) != null) {
                assertThat(target.has(component.getName()))
                        .as("AgentCard component %s must be encoded", component.getName()).isTrue();
            }
        }
        assertThat(target.path("signatures").get(0).path("protected").asText()).isEqualTo("protected-value");
        assertThat(target.path("signatures").get(0).has("protectedHeader")).isFalse();
        assertThat(target.path("capabilities").path("extensions").get(0).path("params").path("mode").asText())
                .isEqualTo("strict");
        assertThat(target.path("securitySchemes").path("api").path("type").asText()).isEqualTo("apiKeySecurityScheme");
        assertThat(target.path("securitySchemes").path("api").path("location").asText()).isEqualTo("header");
    }

    @Test
    void emitsStableFallbackAndIsolatesEncoderFailures() {
        A2AAgentCardResultEncoder encoder = new A2AAgentCardResultEncoder();

        JsonNode ordinaryFallback = encoder
                .encode(IntentRecognitionResult.fallback(IntentRecognitionReason.INSUFFICIENT_MARGIN));
        JsonNode encodingFallback = IntentResultEncoders.encodeOrFallback(result -> {
            throw new IllegalStateException("encoding failed");
        }, IntentRecognitionResult.matched("target"));
        JsonNode nullEncodingFallback = IntentResultEncoders.encodeOrFallback(result -> null,
                IntentRecognitionResult.matched("target"));

        assertThat(ordinaryFallback.toString())
                .isEqualTo("{\"matched\":false,\"target\":null,\"reason\":\"INSUFFICIENT_MARGIN\"}");
        assertThat(encodingFallback.toString())
                .isEqualTo("{\"matched\":false,\"target\":null,\"reason\":\"RESULT_ENCODING_FAILED\"}");
        assertThat(nullEncodingFallback.toString())
                .isEqualTo("{\"matched\":false,\"target\":null,\"reason\":\"RESULT_ENCODING_FAILED\"}");
    }

    private static AgentCard fullCard() {
        AgentSkill skill = new AgentSkill("track", "Track", "Track order", List.of("orders"), List.of("track order 1"),
                List.of("text/plain"), List.of("application/json"),
                List.of(new SecurityRequirement(Map.of("api", List.of()))));
        return new AgentCard("Order Agent", "Order operations",
                new AgentProvider("Example", "https://provider.example"), "1.0.0", "https://docs.example",
                new AgentCapabilities(true, true, true,
                        List.of(new AgentExtension("Required", Map.of("mode", "strict"), true, "urn:required"))),
                List.of("text/plain"), List.of("application/json"), List.of(skill),
                Map.of("api", new APIKeySecurityScheme(APIKeySecurityScheme.Location.HEADER, "X-API-Key", "API key")),
                List.of(new SecurityRequirement(Map.of("api", List.of()))), "https://icon.example",
                List.of(new AgentInterface("JSONRPC", "https://agent.example", "tenant", "1.0")),
                List.of(new AgentCardSignature(Map.of("kid", "key-1"), "protected-value", "signature-value")),
                "https://legacy.example", "JSONRPC",
                List.of(new Legacy_0_3_AgentInterface("JSONRPC", "https://legacy.example")));
    }
}
