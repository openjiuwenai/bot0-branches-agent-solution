/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.formatters;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * date_time_format — parse common patterns and rewrite to target (Python subset without dateutil).
 *
 * @since 2026-08-25
 */
public final class DateTimeFormatValidateAction extends ValidateAction {
    private static final List<String> TIME_FORMATS = List.of(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "HH:mm:ss",
            "HH:mm",
            "yyyyMMdd");

    /**
     * DateTimeFormatValidateAction.
     *
     * @param config config
     */
    public DateTimeFormatValidateAction(ActionConfig config) {
        super(config);
    }

    @Override
    protected ValidationResult validateField(String fieldName, Object value) {
        Object target = extraArgs.get(fieldName);
        if (target == null) {
            return ValidationResult.ok(value);
        }
        if (!(value instanceof String s) || s.isBlank()) {
            return ValidationResult.fail();
        }
        String trimmed = s.trim();
        String targetFmt = toJavaPattern(String.valueOf(target));
        try {
            LocalDateTime dt = tryParse(trimmed);
            if (dt == null) {
                return ValidationResult.fail();
            }
            return ValidationResult.ok(dt.format(DateTimeFormatter.ofPattern(targetFmt)));
        } catch (RuntimeException e) {
            return ValidationResult.fail();
        }
    }

    public static LocalDateTime tryParse(String value) {
        for (String fmt : TIME_FORMATS) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(fmt);
            try {
                if (fmt.contains("HH") && fmt.contains("yyyy")) {
                    return LocalDateTime.parse(value, f);
                }
                if (fmt.contains("yyyy") && !fmt.contains("HH")) {
                    return LocalDate.parse(value, f).atStartOfDay();
                }
                if (fmt.contains("HH") && !fmt.contains("yyyy")) {
                    return LocalDate.now().atTime(LocalTime.parse(value, f));
                }
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        return null;
    }

    public static String toJavaPattern(String py) {
        return py.replace("%Y", "yyyy")
                .replace("%m", "MM")
                .replace("%d", "dd")
                .replace("%H", "HH")
                .replace("%M", "mm")
                .replace("%S", "ss");
    }
}
