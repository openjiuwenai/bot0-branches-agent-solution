/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocesses IntentDetection IR configs (Python {@code _inner_get_config_info} / {@code _get_config_info}).
 *
 * @since 2026-08-26
 */

final class IntentDetectionConfigFormatter {
    private static final Pattern BRANCH_INDEX = Pattern.compile("branch_(\\d+)");
    private static final String DEFAULT_INT = "不确定，其他的意图";

    private IntentDetectionConfigFormatter() {}

    @SuppressWarnings("unchecked")
    static IntentDetectionConfig format(Map<String, Object> configs) {
        List<Map<String, Object>> branches = branchDefs(configs);
        List<String> categoryList = new ArrayList<>();
        List<String> categoryInfoList = new ArrayList<>();
        List<String> categoryNameList = new ArrayList<>();

        for (Map<String, Object> branch : branches) {
            String branchId = str(branch.get("id"));
            Matcher m = BRANCH_INDEX.matcher(branchId);
            if (!m.find()) {
                continue;
            }
            String index = m.group(1);
            String catalog = str(branch.getOrDefault("catalog", branch.get("name")));
            categoryList.add("分类" + index);
            categoryInfoList.add("分类" + index + ": " + catalog);
            categoryNameList.add(catalog);
        }

        String defaultClass = IntentDetectionConfig.DEFAULT_CLASS_ID;
        boolean hasBranch0 = branches.stream().anyMatch(b -> "branch_0".equals(str(b.get("id"))));
        if (!hasBranch0) {
            defaultClass = IntentDetectionConfig.DEFAULT_CLASS_ID_LEGACY;
        }
        if (categoryNameList.contains(DEFAULT_INT)) {
            int idx = categoryNameList.indexOf(DEFAULT_INT);
            defaultClass = "分类" + idx;
        }

        String userPrompt = str(configs.getOrDefault("prompt", configs.get("userPrompt")));
        boolean enableHistory = bool(configs.get("enableHistory"), false);
        boolean enableInput = bool(configs.getOrDefault("enableInput", configs.get("enable_input")), true);
        int maxTurn = intOf(configs.getOrDefault("chatHistoryMaxTurn", configs.get("chat_history_max_turn")), 3);
        List<String> examples = new ArrayList<>();
        Object ex = configs.getOrDefault("exampleContent", configs.get("example_content"));
        if (ex instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    examples.add(String.valueOf(item));
                }
            }
        }
        boolean overridable = bool(configs.get("overridable"), false);
        boolean enableKnowledge = bool(configs.getOrDefault("enableKnowledge", configs.get("enable_knowledge")), false);
        double recall = doubleOf(configs.getOrDefault("recallThreshold", configs.get("recall_threshold")), 0.9);
        double q2label = doubleOf(
                configs.getOrDefault("q2labelFewShotScore", configs.get("q2label_few_shot_score")), 0.7);
        Map<String, Object> kg = mapOf(configs.get("kg"));
        String kgScope = str(kg.getOrDefault("scope", "faq"));
        if (kgScope.isBlank()) {
            kgScope = "faq";
        }

        Map<String, Object> llm = mapOf(configs.get("llm"));
        Map<String, Object> model = mapOf(llm.getOrDefault("model", configs.get("model")));
        String modelName = str(first(model, "modelName", "model_name", "name"));
        String modelType = str(first(model, "modelType", "model_type", "type"));
        Map<String, Object> hyper = new LinkedHashMap<>();
        Object hyperRaw = firstObj(model, "hyperParameters", "hyper_parameters");
        if (hyperRaw instanceof Map<?, ?> hm) {
            hm.forEach((k, v) -> hyper.put(String.valueOf(k), v));
        }
        Map<String, Object> extension = mapOf(model.get("extension"));

        return new IntentDetectionConfig(
                userPrompt,
                String.join("\n", categoryInfoList),
                categoryList,
                categoryNameList,
                defaultClass,
                enableHistory,
                enableInput,
                maxTurn,
                examples,
                overridable,
                enableKnowledge,
                recall,
                q2label,
                kg,
                kgScope,
                modelName,
                modelType,
                hyper,
                extension,
                branches,
                configs);
    }

    /**
     * Python {@code reset} default_class override when branch_0 is absent.
     *
     * @param base base
     * @param defaultClass defaultClass
     * @return result
     * @since 0.1.0
     */
    static IntentDetectionConfig withDefaultClass(IntentDetectionConfig base, String defaultClass) {
        return new IntentDetectionConfig(
                base.userPrompt(),
                base.categoryInfo(),
                base.categoryList(),
                base.categoryNameList(),
                defaultClass,
                base.enableHistory(),
                base.enableInput(),
                base.chatHistoryMaxTurn(),
                base.exampleContent(),
                base.overridable(),
                base.enableKnowledge(),
                base.recallThreshold(),
                base.q2labelFewShotScore(),
                base.kgConfig(),
                base.kgScope(),
                base.modelName(),
                base.modelType(),
                base.hyperParameters(),
                base.extension(),
                base.branches(),
                base.rawConfigs());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> branchDefs(Map<String, Object> configs) {
        Object branches = configs.get("branches");
        List<Map<String, Object>> out = new ArrayList<>();
        if (branches instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> cast = new LinkedHashMap<>();
                    m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                    out.add(cast);
                }
            }
        }
        return out;
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
