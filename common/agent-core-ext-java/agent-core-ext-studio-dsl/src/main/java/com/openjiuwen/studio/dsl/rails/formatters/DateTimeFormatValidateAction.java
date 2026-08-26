/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.formatters;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * date_time_format — parse via {@link DateUtilCompatibleParser} (dateutil-compatible fuzzy parse).
 *
 * @since 2026-08-25
 */
public final class DateTimeFormatValidateAction extends ValidateAction {
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
        return DateUtilCompatibleParser.tryParse(value);
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
