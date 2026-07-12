/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentResultEncoder;
import java.util.Objects;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCardSignature;
import org.a2aproject.sdk.spec.SecurityScheme;

/** Encodes official A2A AgentCard results using A2A protocol JSON field names. */
public final class A2AAgentCardResultEncoder implements IntentResultEncoder<AgentCard> {
    private static final ObjectMapper OBJECT_MAPPER = createMapper();

    @Override
    public JsonNode encode(IntentRecognitionResult<AgentCard> result) {
        Objects.requireNonNull(result, "result must not be null");
        ObjectNode envelope = OBJECT_MAPPER.createObjectNode();
        envelope.put("matched", result.matched());
        if (result.target() == null) {
            envelope.putNull("target");
        } else {
            envelope.set("target", OBJECT_MAPPER.valueToTree(result.target()));
        }
        envelope.put("reason", result.reason().name());
        return envelope;
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.addMixIn(AgentCardSignature.class, AgentCardSignatureMixin.class);
        mapper.addMixIn(SecurityScheme.class, SecuritySchemeMixin.class);
        mapper.addMixIn(APIKeySecurityScheme.Location.class, APIKeyLocationMixin.class);
        return mapper;
    }

    private abstract static class AgentCardSignatureMixin {
        @JsonProperty("protected")
        abstract String protectedHeader();
    }

    private abstract static class SecuritySchemeMixin {
        @JsonProperty("type")
        abstract String type();
    }

    private abstract static class APIKeyLocationMixin {
        @JsonValue
        abstract String asString();
    }
}
