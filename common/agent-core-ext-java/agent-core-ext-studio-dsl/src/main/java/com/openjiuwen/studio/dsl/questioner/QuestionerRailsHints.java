/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rails constraint hints for LLM prompts and continue-ask questions
 * (Python {@code _get_rails_constraints_for_field} / {@code _format_field_constraint_hint}).
 *
 * @since 2026-08-26
 */

final class QuestionerRailsHints {
    private static final String CONTINUE_ASK_ZH = "请您提供{non_extracted_key_fields_names}相关的信息";
    private static final String CONTINUE_ASK_EN =
            "Please provide information related to: {non_extracted_key_fields_names}";

    private static final Map<String, String> FORMAT_DESCRIPTIONS =
            Map.ofEntries(
                    Map.entry("bank_card", "银行卡号"),
                    Map.entry("phone", "手机号码"),
                    Map.entry("phone_with_country_code", "国际电话"),
                    Map.entry("passport", "护照号码"),
                    Map.entry("email", "邮箱地址"),
                    Map.entry("url", "网址"),
                    Map.entry("ip_address", "IP地址"),
                    Map.entry("mac_address", "MAC地址"),
                    Map.entry("postal_code", "邮政编码"),
                    Map.entry("uuid", "UUID"),
                    Map.entry("license_plate", "车牌号"));

    private static final Map<String, String> DATETIME_HINTS =
            Map.of(
                    "%Y-%m-%d %H:%M:%S", "2025-02-03 14:30:00（年月日 时分秒）",
                    "%Y-%m-%d %H:%M", "2025-02-03 14:30（年月日 时分）",
                    "%Y-%m-%d", "2025-02-03（年月日）",
                    "%Y/%m/%d %H:%M:%S", "2025/02/03 14:30:00",
                    "%Y/%m/%d", "2025/02/03",
                    "%d/%m/%Y %H:%M:%S", "03/02/2025 14:30:00",
                    "%d/%m/%Y", "03/02/2025",
                    "%m/%d/%Y %H:%M:%S", "02/03/2025 14:30:00",
                    "%m/%d/%Y", "02/03/2025");

    private QuestionerRailsHints() {}

    static Map<String, Object> constraintsForField(QuestionerConfig config, String fieldName) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("enum_values", "");
        constraints.put("length_limit", null);
        constraints.put("number_range", null);
        constraints.put("format_type", "");
        constraints.put("date_time_format", "");

        Map<String, Object> rails = config.railsConfig();
        if (rails == null || rails.isEmpty()) {
            return constraints;
        }
        Object actions = rails.get("actions_config");
        if (!(actions instanceof List<?> list)) {
            return constraints;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> action)) {
                continue;
            }
            Object extra = action.get("action_extra_args");
            if (!(extra instanceof Map<?, ?> em) || !em.containsKey(fieldName)) {
                continue;
            }
            String actionName = String.valueOf(action.get("action"));
            Object arg = em.get(fieldName);
            switch (actionName) {
                case "enum_legality_validate" -> {
                    if (arg instanceof List<?> enums) {
                        constraints.put(
                                "enum_values",
                                enums.stream().map(String::valueOf).collect(Collectors.joining(" / ")));
                    }
                }
                case "length_limit_validate" -> {
                    try {
                        constraints.put("length_limit", Integer.parseInt(String.valueOf(arg)));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
                case "number_range_validate" -> {
                    if (arg instanceof List<?> range && range.size() == 2) {
                        constraints.put("number_range", List.of(range.get(0), range.get(1)));
                    }
                }
                case "common_data_format_check" -> constraints.put("format_type", String.valueOf(arg));
                case "date_time_format" -> constraints.put("date_time_format", String.valueOf(arg));
                default -> { }
            }
        }
        return constraints;
    }

    static String dateTimeFormatConstraint(QuestionerConfig config, String fieldName) {
        Object fmt = constraintsForField(config, fieldName).get("date_time_format");
        if (fmt == null) {
            return null;
        }
        String s = String.valueOf(fmt);
        return s.isBlank() ? null : s;
    }

    static String formatConstraintsForPrompt(QuestionerConfig config, String fieldName) {
        return formatConstraintParts(config, constraintsForField(config, fieldName), true);
    }
    static String formatConstraintHint(QuestionerConfig config, String fieldName) {
        if (!config.enumVisible()) {
        return "";
    }
        return formatConstraintParts(config, constraintsForField(config, fieldName), false);
    }

    private static String formatConstraintParts(
            QuestionerConfig config, Map<String, Object> constraints, boolean forPrompt) {
        List<String> parts = new ArrayList<>();
        boolean en = "en".equalsIgnoreCase(config.acceptLanguage());

        Object enumValues = constraints.get("enum_values");
        if (enumValues != null && !String.valueOf(enumValues).isBlank()) {
            if (en) {
                parts.add(forPrompt ? "(valid values: " + enumValues + ")" : "(valid values: " + enumValues + ")");
            } else {
                parts.add(forPrompt ? "（有效值：" + enumValues + "）" : "（可选值：" + enumValues + "）");
            }
        }

        Object lengthLimit = constraints.get("length_limit");
        if (lengthLimit instanceof Number n) {
            int limit = n.intValue();
            if (en) {
                parts.add(forPrompt ? "(max length: " + limit + " characters)" : "(max " + limit + " chars)");
            } else {
                parts.add(forPrompt ? "（最多" + limit + "个字符）" : "（最多" + limit + "字符）");
            }
        }

        Object numberRange = constraints.get("number_range");
        if (numberRange instanceof List<?> range && range.size() == 2) {
            if (en) {
                parts.add("(range: " + range.get(0) + "-" + range.get(1) + ")");
            } else {
                parts.add(forPrompt
                        ? "（取值范围：" + range.get(0) + "-" + range.get(1) + "）"
                        : "（范围：" + range.get(0) + "-" + range.get(1) + "）");
            }
        }

        Object formatType = constraints.get("format_type");
        if (formatType != null && !String.valueOf(formatType).isBlank()) {
            String desc = formatDescription(String.valueOf(formatType));
            if (!desc.isBlank()) {
                parts.add(en ? "(" + desc + ")" : "（" + desc + "）");
            }
        }

        Object dateFormat = constraints.get("date_time_format");
        if (dateFormat != null && !String.valueOf(dateFormat).isBlank()) {
            String hint = datetimeFormatHint(String.valueOf(dateFormat));
            if (en) {
                parts.add("(format: " + hint + ")");
            } else {
                parts.add("（格式：" + hint + "）");
            }
        }

        return String.join(" ", parts);
    }

    static String constructQuestionWithConstraints(QuestionerConfig config, List<QuestionerField> missing) {
        List<String> names = new ArrayList<>();
        for (QuestionerField field : missing) {
            String cn = field.cnFieldName().isBlank() ? field.description() : field.cnFieldName();
            String hint = formatConstraintHint(config, field.fieldName());
            names.add(hint.isBlank() ? cn : cn + hint);
        }
        String joined = String.join(", ", names);
        if (!config.autoAskTemplate().isBlank()) {
            return config.autoAskTemplate().replace("{unextracted_cn_field_names}", joined);
        }
        if ("en".equalsIgnoreCase(config.acceptLanguage())) {
            return "Please provide your " + joined;
        }
        return "请您提供" + joined + "相关的信息";
    }

    static String formatContinueAskQuestion(QuestionerConfig config, List<QuestionerField> missing) {
        String names =
                missing.stream()
                        .map(f -> f.cnFieldName().isBlank() ? f.description() : f.cnFieldName())
                        .collect(Collectors.joining(", "));
        String template = "en".equalsIgnoreCase(config.acceptLanguage()) ? CONTINUE_ASK_EN : CONTINUE_ASK_ZH;
        return template.replace("{non_extracted_key_fields_names}", names);
    }

    static String constructContinueQuestion(QuestionerConfig config, List<QuestionerField> missing) {
        if ("llm_based".equalsIgnoreCase(config.questionConstructionMethod())) {
        return formatContinueAskQuestion(config, missing);
    }
        return constructQuestionWithConstraints(config, missing);
    }

    static String constructConfirmationQuestion(QuestionerConfig config, Map<String, Object> extracted) {
        boolean en = "en".equalsIgnoreCase(config.acceptLanguage());
        List<String> lines = new ArrayList<>();
        lines.add(en ? "The following information has been collected:" : "以下是收集到的信息：");
        for (QuestionerField field : config.keyFields()) {
            Object value = extracted.get(field.fieldName());
            if (value == null || "".equals(value)) {
                continue;
            }
            String cn = field.cnFieldName().isBlank() ? field.description() : field.cnFieldName();
            lines.add(cn + ": " + value);
        }
        if (en) {
            lines.add("If the information is correct, please enter \"confirm\"; ");
            lines.add("If you need to make changes, please enter the modified content.");
        } else {
            lines.add("如果信息无误请输入\"确认\"；");
            lines.add("如果需要调整，请输入需要修改的内容，建议格式\"名称：目标值\"");
        }
        return String.join("\n", lines);
    }

    static List<Map<String, Object>> fieldOutputsForRails(QuestionerConfig config) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (QuestionerField f : config.keyFields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field_name", f.fieldName());
            m.put("description", f.description());
            m.put("type", f.type());
            m.put("cn_field_name", f.cnFieldName());
            m.put("required", f.required());
            m.put("default_value", f.defaultValue());
            m.put("reflection", f.reflection());
            outputs.add(m);
        }
        return outputs;
    }

    private static String formatDescription(String formatType) {
        return FORMAT_DESCRIPTIONS.getOrDefault(formatType, "");
    }
    private static String datetimeFormatHint(String dateFormat) {
        if (DATETIME_HINTS.containsKey(dateFormat)) {
        return DATETIME_HINTS.get(dateFormat);
    }
        return dateFormat
                .replace("%Y", "2025")
                .replace("%y", "25")
                .replace("%m", "02")
                .replace("%d", "03")
                .replace("%H", "14")
                .replace("%M", "30")
                .replace("%S", "00")
                .replace("%I", "02")
                .replace("%p", "PM");
    }
}
