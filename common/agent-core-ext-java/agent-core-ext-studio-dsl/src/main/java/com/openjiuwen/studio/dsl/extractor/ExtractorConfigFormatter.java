/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preprocesses Extractor IR configs (Python {@code format_extractor_configs}).
 *
 * @since 2026-08-26
 */
final class ExtractorConfigFormatter {
    private static final String DEFAULT_EXAMPLE =
            """
            用户输入: 我是小明，性别是男
            指定参数：[name:姓名, age:年龄, gender:性别]
            提取结果：{"name":"小明","age":null,"gender":"男"}
            """;

    private ExtractorConfigFormatter() {}

    @SuppressWarnings("unchecked")
    static ExtractorConfig format(Map<String, Object> configs) {
        Map<String, Object> c = camelKeysToSnake(configs);
        Map<String, Object> model = mapOf(c.get("model"));

        String modelName = str(first(model, "model_name", "modelName", "name"));
        if (modelName.isBlank() && c.get("model") != null && !(c.get("model") instanceof Map<?, ?>)) {
            modelName = String.valueOf(c.get("model"));
        }

        String modelType = str(first(model, "model_type", "modelType", "type"));
        Map<String, Object> hyper = new LinkedHashMap<>();
        Object hyperRaw = firstObj(model, "hyper_parameters", "hyperParameters");
        if (hyperRaw instanceof Map<?, ?> hm) {
            hm.forEach((k, v) -> hyper.put(String.valueOf(k), v));
        }
        double topP = doubleOf(hyper.get("top_p"), 0.15);
        double temp = doubleOf(hyper.get("temperature"), 0.1);
        hyper.putIfAbsent("top_p", topP);
        hyper.putIfAbsent("temperature", temp);

        Map<String, Object> extension = mapOf(model.get("extension"));

        String questionContent = str(c.getOrDefault("question_content", c.get("question")));
        String extra = str(c.get("extra_prompt_for_fields_extraction"));

        Map<String, String> cnFields = new LinkedHashMap<>();
        List<Map<String, Object>> keyFields = new ArrayList<>();
        Object fieldNames = c.get("field_names");
        if (fieldNames == null) {
            fieldNames = c.get("fieldNames");
        }
        if (fieldNames instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String fname = str(first(m, "field_name", "fieldName", "name"));
                String cn = str(first(m, "cn_field_name", "cnFieldName", fname));
                String desc = str(first(m, "description", "desc"));
                if (!fname.isBlank()) {
                    cnFields.put(fname, cn.isBlank() ? fname : cn);
                    Map<String, Object> kf = new LinkedHashMap<>();
                    kf.put("name", fname);
                    kf.put("desc", desc);
                    kf.put("default_value", m.get("default_value"));
                    keyFields.add(kf);
                }
            }
        } else if (c.get("extraConfig") instanceof List<?> legacy) {
            for (Object item : legacy) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String name = str(m.get("name"));
                String cn = str(m.get("questionKeyWord"));
                String desc = str(m.get("description"));
                if (!name.isBlank()) {
                    cnFields.put(name, cn.isBlank() ? name : cn);
                    keyFields.add(Map.of("name", name, "desc", desc));
                }
            }
        }

        boolean withChat = bool(c.get("with_chat_history"), false) || bool(c.get("withChatHistory"), false);
        int maxRounds = intOf(c.getOrDefault("chat_history_max_rounds", c.get("chatHistoryMaxRounds")), 0);

        List<Map<String, String>> promptTpl = new ArrayList<>();
        Object pt = c.getOrDefault("prompt_template", c.get("promptTemplate"));
        if (pt instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> turn = new LinkedHashMap<>();
                    turn.put("role", str(m.get("role")));
                    turn.put("content", str(m.get("content")));
                    promptTpl.add(turn);
                }
            }
        }

        String example = str(c.getOrDefault("example_content", c.get("exampleContent")));
        if (example.isBlank()) {
            example = DEFAULT_EXAMPLE;
        }

        Boolean inputComplement = nullableBool(c.get("input_complement"));
        Boolean extractFieldsFromResponse = nullableBool(c.get("extract_fields_from_response"));

        return new ExtractorConfig(
                modelName,
                modelType,
                withChat,
                maxRounds,
                extra,
                questionContent,
                cnFields,
                keyFields,
                hyper,
                extension,
                promptTpl,
                example,
                inputComplement,
                extractFieldsFromResponse,
                configs);
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

    private static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String first(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static Object firstObj(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
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

    private static Boolean nullableBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return null;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleOf(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
