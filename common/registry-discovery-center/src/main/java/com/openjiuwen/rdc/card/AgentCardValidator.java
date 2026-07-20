/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Validates standard A2A Agent Card JSON per Feat-015 0711 scope §5.1.2.
 * Runtime-only — uses Jackson, not part of SPI.
 *
 * @since 0.1.0
 */
public final class AgentCardValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private AgentCardValidator() {
    }

    public static ValidationResult validate(String cardJson) {
        if (cardJson == null || cardJson.isBlank()) {
            return ValidationResult.invalid("AGENT_CARD_INVALID", "empty card body");
        }
        if (cardJson.length() > MAX_RESPONSE_BYTES) {
            return ValidationResult.invalid("AGENT_CARD_INVALID", "card exceeds size limit");
        }
        try {
            JsonNode root = MAPPER.readTree(cardJson);
            requireText(root, "name");
            requireText(root, "description");
            requireText(root, "version");
            requireNonEmptyArray(root, "defaultInputModes");
            requireNonEmptyArray(root, "defaultOutputModes");
            validateCapabilities(root);
            JsonNode skills = root.get("skills");
            if (skills == null || !skills.isArray()) {
                return ValidationResult.invalid("AGENT_CARD_INVALID", "skills must be an array");
            }
            for (JsonNode skill : skills) {
                ValidationResult skillResult = validateSkill(skill);
                if (!skillResult.valid()) {
                    return skillResult;
                }
            }
            JsonNode interfaces = root.get("supportedInterfaces");
            if (interfaces == null || !interfaces.isArray() || interfaces.isEmpty()) {
                return ValidationResult.invalid("AGENT_CARD_INVALID", "supportedInterfaces required");
            }
            JsonNode selectedInterface = null;
            for (JsonNode iface : interfaces) {
                if (isJsonRpcInterface(iface)) {
                    selectedInterface = iface;
                    break;
                }
            }
            if (selectedInterface == null) {
                return ValidationResult.invalid(
                        "AGENT_CARD_INVALID", "no platform-supported JSON-RPC interface");
            }
            String contractVersion = textOrNull(selectedInterface, "protocolVersion");
            if (contractVersion == null || contractVersion.isBlank()) {
                contractVersion = textOrNull(selectedInterface, "version");
            }
            return ValidationResult.valid(
                    root.path("version").asText(null),
                    contractVersion);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            // Schema/field checks throw IllegalArgumentException; map to AGENT_CARD_INVALID
            // rather than propagating (callers expect ValidationResult, not unchecked).
            return ValidationResult.invalid("AGENT_CARD_INVALID", ex.getMessage());
        }
    }

    public static boolean hasCapability(JsonNode root, String capability) {
        JsonNode caps = root.get("capabilities");
        if (caps == null || !caps.isObject()) {
            return false;
        }
        JsonNode node = caps.get(capability);
        return node != null && node.isBoolean() && node.asBoolean();
    }

    public static boolean supportsInputMode(JsonNode root, JsonNode skill, String mode) {
        JsonNode modes = skill != null ? skill.get("inputModes") : null;
        if (modes == null || !modes.isArray() || modes.isEmpty()) {
            modes = root.get("defaultInputModes");
        }
        return arrayContainsText(modes, mode);
    }

    public static boolean supportsOutputMode(JsonNode root, JsonNode skill, String mode) {
        JsonNode modes = skill != null ? skill.get("outputModes") : null;
        if (modes == null || !modes.isArray() || modes.isEmpty()) {
            modes = root.get("defaultOutputModes");
        }
        return arrayContainsText(modes, mode);
    }

    public static boolean supportsSecurityScheme(JsonNode root, String scheme) {
        JsonNode schemes = root.get("securitySchemes");
        if (schemes == null || !schemes.isObject()) {
            return false;
        }
        return schemes.has(scheme);
    }

    private static void validateCapabilities(JsonNode root) {
        JsonNode caps = root.get("capabilities");
        if (caps == null || !caps.isObject()) {
            throw new IllegalArgumentException("capabilities must be an object");
        }
    }

    private static ValidationResult validateSkill(JsonNode skill) {
        try {
            requireText(skill, "id");
            requireText(skill, "name");
            requireText(skill, "description");
            if (!skill.has("tags") || !skill.get("tags").isArray()) {
                return ValidationResult.invalid("AGENT_CARD_INVALID", "skill tags must be an array");
            }
            return ValidationResult.valid(null, null);
        } catch (IllegalArgumentException ex) {
            return ValidationResult.invalid("AGENT_CARD_INVALID", ex.getMessage());
        }
    }

    private static boolean arrayContainsText(JsonNode array, String value) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    public static boolean skillTagsContain(JsonNode cardRoot, Iterable<String> requiredTags) {
        JsonNode skills = cardRoot.get("skills");
        if (skills == null || !skills.isArray()) {
            return false;
        }
        for (String tag : requiredTags) {
            if (!cardHasTag(skills, tag)) {
                return false;
            }
        }
        return true;
    }

    private static boolean cardHasTag(JsonNode skills, String tag) {
        for (JsonNode skill : skills) {
            JsonNode tags = skill.get("tags");
            if (tags != null && tags.isArray()) {
                for (JsonNode t : tags) {
                    if (tag.equals(t.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-empty text");
        }
    }

    private static void requireNonEmptyArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty array");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && child.isTextual() ? child.asText() : null;
    }

    private static boolean isJsonRpcInterface(JsonNode iface) {
        return isJsonRpcNode(iface);
    }

    static boolean isJsonRpcNode(JsonNode iface) {
        for (String field : List.of("protocolBinding", "transport", "protocol", "binding")) {
            String value = textOrNull(iface, field);
            if (value != null && value.toLowerCase(java.util.Locale.ROOT).contains("jsonrpc")) {
                return true;
            }
        }
        return false;
    }

    public record ValidationResult(boolean valid, String failureCode, String message,
                                   String capabilityVersion, String contractVersion) {
        static ValidationResult valid(String capabilityVersion, String contractVersion) {
            return new ValidationResult(true, null, null, capabilityVersion, contractVersion);
        }

        static ValidationResult invalid(String failureCode, String message) {
            return new ValidationResult(false, failureCode, message, null, null);
        }
    }
}
