/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict Extractor IR validation (Python {@code flow_extractor.Extractor.check_config} /
 * {@code match_error_config} / {@code prompt_template_check}).
 *
 * @since 2026-08-26
 */
public final class ExtractorConfigValidator {
    private static final String NODE_TYPE = "jiuwen.extractor";

    private ExtractorConfigValidator() {}

    /**
     * Validates raw node configs (camelCase IR) after top-level snake_case conversion, matching Python {@code init}.
     */
    public static void checkConfig(String nodeId, Map<String, Object> rawConfigs) {
        Map<String, Object> conf = camelKeysToSnake(rawConfigs == null ? Map.of() : rawConfigs);
        String error = matchErrorConfig(conf);
        if (error != null) {
            throw new NodeExecutionException(nodeId, NODE_TYPE, NodeCauseCode.NODE_CONFIG_INVALID, error);
        }
    }

    /**
     * @return error message when illegal, {@code null} when valid
     */
    static String matchErrorConfig(Map<String, Object> conf) {
        Map<String, Object> model = modelOf(conf.get("model"));
        Object modelName = model.get("modelName");
        Object modelType = model.get("modelType");
        if (!(modelName instanceof String) || !(modelType instanceof String)) {
            return "Model name or model type is not in extractor configuration.";
        }

        Map<String, Object> hyper = mapOf(model.get("hyperParameters"));
        Object temperature = hyper.getOrDefault("temperature", 0);
        if (!(temperature instanceof Number)) {
            return "Temperature is illegal in extractor configuration.";
        }

        String promptError = promptTemplateCheck(conf);
        if (promptError != null) {
            return promptError;
        }

        Object extraPrompt = conf.get("extra_prompt_for_fields_extraction");
        if (extraPrompt != null && !(extraPrompt instanceof String)) {
            return "Extra prompt is illegal in extractor configuration.";
        }

        Object questionContent = conf.get("question_content");
        if (questionContent != null && !(questionContent instanceof String)) {
            return "Question content is illegal in extractor configuration.";
        }

        for (String key : List.of("input_complement", "with_chat_history", "extract_fields_from_response")) {
            Object value = conf.get(key);
            if (value != null && !(value instanceof Boolean)) {
                return key + " is illegal in extractor configuration.";
            }
        }

        Object fieldNames = conf.get("field_names");
        if (fieldNames != null && !(fieldNames instanceof List<?>)) {
            return "field_names should be a list in extractor configuration.";
        }
        if (fieldNames instanceof List<?> list) {
            for (Object unit : list) {
                if (!(unit instanceof Map<?, ?> m)) {
                    return "field_name, description and cn_field_name should be in field_names "
                            + "in extractor configuration.";
                }
                if (!m.containsKey("field_name") || !m.containsKey("description") || !m.containsKey("cn_field_name")) {
                    return "field_name, description and cn_field_name should be in field_names "
                            + "in extractor configuration.";
                }
            }
        }

        return null;
    }

    static String promptTemplateCheck(Map<String, Object> conf) {
        Object promptTemplate = conf.get("prompt_template");
        if (promptTemplate == null) {
            return null;
        }
        if (!(promptTemplate instanceof List<?> list)) {
            return "Prompt Template should be a list in extractor configuration.";
        }
        for (Object unit : list) {
            if (!(unit instanceof Map<?, ?> m)) {
                return "Prompt Template should be with role and content in extractor configuration.";
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (!(role instanceof String) || !(content instanceof String)) {
                return "Prompt Template should be with role and content in extractor configuration.";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelOf(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Map<String, Object> camelKeysToSnake(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((k, v) -> out.put(camelToSnake(k), v));
        return out;
    }

    private static String camelToSnake(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toLowerCase(text.charAt(0)));
        for (int i = 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
