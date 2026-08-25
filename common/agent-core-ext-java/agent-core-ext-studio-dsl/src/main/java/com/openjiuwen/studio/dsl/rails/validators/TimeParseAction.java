/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.validators;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.FormatAction;
import com.openjiuwen.studio.dsl.rails.ValidateAction;
import com.openjiuwen.studio.dsl.rails.formatters.DateTimeFormatValidateAction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * parse_time — subset of Python TimeParseAction (今天/明天/后天 + ISO formats).
 *
 * @since 2026-08-25
 */
public final class TimeParseAction extends FormatAction {
    private static final Pattern RELATIVE =
            Pattern.compile("^(今天|明天|后天|大后天)(?:\\s*(\\d{1,2})[点时:](\\d{0,2})?)?");

    /**
     * TimeParseAction.
     *
     * @param config config
     */
    public TimeParseAction(ActionConfig config) {
        super(config);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        // parse_time applies to all argument values when format is global
        String format = String.valueOf(extraArgs.getOrDefault("format", "%Y-%m-%d %H:%M"));
        String javaFmt = DateTimeFormatValidateAction.toJavaPattern(format);
        Map<String, Object> args = ValidateAction.argsOf(context);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            out.put(e.getKey(), formatValue(e.getValue(), javaFmt));
        }
        return wrapArgs(out);
    }

    @Override
    protected Object formatField(String fieldName, Object value) {
        String format = String.valueOf(extraArgs.getOrDefault("format", "%Y-%m-%d %H:%M"));
        return formatValue(value, DateTimeFormatValidateAction.toJavaPattern(format));
    }

    private static Object formatValue(Object value, String javaFmt) {
        if (!(value instanceof String s) || s.isBlank()) {
            return value;
        }
        String trimmed = s.trim();
        Matcher m = RELATIVE.matcher(trimmed);
        if (m.find()) {
            LocalDate base = LocalDate.now();
            base = switch (m.group(1)) {
                case "明天" -> base.plusDays(1);
                case "后天" -> base.plusDays(2);
                case "大后天" -> base.plusDays(3);
                default -> base;
            };
            int hour = m.group(2) == null || m.group(2).isEmpty() ? 0 : Integer.parseInt(m.group(2));
            int minute = m.group(3) == null || m.group(3).isEmpty() ? 0 : Integer.parseInt(m.group(3));
            LocalDateTime dt = base.atTime(hour, minute);
            return dt.format(DateTimeFormatter.ofPattern(javaFmt));
        }
        LocalDateTime parsed = DateTimeFormatValidateAction.tryParse(trimmed);
        if (parsed != null) {
            return parsed.format(DateTimeFormatter.ofPattern(javaFmt));
        }
        return value;
    }
}
