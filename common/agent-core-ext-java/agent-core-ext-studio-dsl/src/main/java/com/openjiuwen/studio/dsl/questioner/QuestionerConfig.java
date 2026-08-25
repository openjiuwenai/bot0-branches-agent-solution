/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Questioner IR config (Python {@code QuestionerConfig} subset).
 *
 * @since 2026-08-25
 */
public final class QuestionerConfig {
    private final String questionContent;
    private final boolean extractFieldsFromResponse;
    private final String questionConstructionMethod;
    private final int maxResponse;
    private final String acceptLanguage;
    private final String autoAskTemplate;
    private final boolean allowNodeConfirm;
    private final boolean allowNodeBreak;
    private final boolean enumVisible;
    private final List<QuestionerField> keyFields;
    private final Map<String, Object> railsConfig;
    private final Map<String, Object> mockExtractedFields;

    QuestionerConfig(
            String questionContent,
            boolean extractFieldsFromResponse,
            String questionConstructionMethod,
            int maxResponse,
            String acceptLanguage,
            String autoAskTemplate,
            boolean allowNodeConfirm,
            boolean allowNodeBreak,
            boolean enumVisible,
            List<QuestionerField> keyFields,
            Map<String, Object> railsConfig,
            Map<String, Object> mockExtractedFields) {
        this.questionContent = questionContent;
        this.extractFieldsFromResponse = extractFieldsFromResponse;
        this.questionConstructionMethod = questionConstructionMethod;
        this.maxResponse = maxResponse;
        this.acceptLanguage = acceptLanguage;
        this.autoAskTemplate = autoAskTemplate;
        this.allowNodeConfirm = allowNodeConfirm;
        this.allowNodeBreak = allowNodeBreak;
        this.enumVisible = enumVisible;
        this.keyFields = List.copyOf(keyFields);
        this.railsConfig = railsConfig;
        this.mockExtractedFields = mockExtractedFields;
    }

    /**
     * fromNodeConfigs.
     *
     * @param configs configs
     * @return result
     */
    @SuppressWarnings("unchecked")
    public static QuestionerConfig fromNodeConfigs(Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String questionContent = str(c.getOrDefault("questionContent", c.getOrDefault("question", c.get("prompt"))));
        boolean extract = bool(c.get("extractFieldsFromResponse"), true);
        String method = str(c.getOrDefault("questionConstructionMethod", "rule_based"));
        if (method.isBlank()) {
            method = "rule_based";
        }
        int maxResponse = 3;
        Object mr = c.get("maxResponse");
        if (mr instanceof Number n) {
            maxResponse = n.intValue();
        }
        String lang = str(c.getOrDefault("acceptLanguage", "zh"));
        String template = str(c.get("autoAskTemplate"));
        boolean allowConfirm = bool(c.get("allowNodeConfirm"), false);
        boolean allowBreak = bool(c.get("allowNodeBreak"), false);
        boolean enumVisible = bool(c.get("enumVisible"), true);
        List<QuestionerField> fields = parseFields(c);
        Map<String, Object> rails = mapOf(c.get("railsConfig"));
        Map<String, Object> mock = mapOf(c.get("mockExtractedFields"));
        return new QuestionerConfig(
                questionContent,
                extract,
                method,
                maxResponse,
                lang,
                template,
                allowConfirm,
                allowBreak,
                enumVisible,
                fields,
                rails,
                mock);
    }

    @SuppressWarnings("unchecked")
    private static List<QuestionerField> parseFields(Map<String, Object> c) {
        Object raw = c.get("fieldNames");
        if (raw == null) {
            raw = c.get("keyFields");
        }
        if (raw == null) {
            Object uf = c.get("userFields");
            if (uf instanceof Map<?, ?> m) {
                raw = m.get("inputs");
            }
        }
        List<QuestionerField> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> cast = new LinkedHashMap<>();
                    m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                    out.add(QuestionerField.fromMap(cast));
                } else if (item instanceof String s) {
                    out.add(new QuestionerField(s, "", "string", s, true, null, false));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return def;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    /** @return questionContent */
    public String questionContent() {
        return questionContent;
    }

    /** @return extractFieldsFromResponse */
    public boolean extractFieldsFromResponse() {
        return extractFieldsFromResponse;
    }

    /** @return questionConstructionMethod */
    public String questionConstructionMethod() {
        return questionConstructionMethod;
    }

    /** @return maxResponse */
    public int maxResponse() {
        return maxResponse;
    }

    /** @return acceptLanguage */
    public String acceptLanguage() {
        return acceptLanguage;
    }

    /** @return autoAskTemplate */
    public String autoAskTemplate() {
        return autoAskTemplate;
    }

    /** @return allowNodeConfirm */
    public boolean allowNodeConfirm() {
        return allowNodeConfirm;
    }

    /** @return allowNodeBreak */
    public boolean allowNodeBreak() {
        return allowNodeBreak;
    }

    /** @return enumVisible */
    public boolean enumVisible() {
        return enumVisible;
    }

    /** @return keyFields */
    public List<QuestionerField> keyFields() {
        return keyFields;
    }

    /** @return railsConfig */
    public Map<String, Object> railsConfig() {
        return railsConfig;
    }

    /** @return mockExtractedFields */
    public Map<String, Object> mockExtractedFields() {
        return mockExtractedFields;
    }

    /** @return has question content */
    public boolean hasQuestionContent() {
        return questionContent != null && !questionContent.isBlank();
    }

    /** @return need extract */
    public boolean needExtractFields() {
        return extractFieldsFromResponse && !keyFields.isEmpty();
    }
}
