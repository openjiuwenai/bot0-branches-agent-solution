/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validate-style rails action: failed fields become {@code null} (Python {@code ValidateAction}).
 *
 * @since 2026-08-25
 */

public abstract class ValidateAction extends RailsAction {

    /**
     * ValidateAction.
     *
     * @param config config
     */

    protected ValidateAction(ActionConfig config) {
        super(config);
    }

    /**
     * validateField.
     *
     * @param fieldName fieldName
     * @param value value
     * @return valid + possibly normalized value
     */

    protected abstract ValidationResult validateField(String fieldName, Object value);

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> context) {
        Map<String, Object> args = argsOf(context);
        Map<String, Object> validated = new LinkedHashMap<>();
        for (String key : extraArgs.keySet()) {
            Object value = args.get(key);
            if (value == null) {
                validated.put(key, null);
                continue;
            }
            ValidationResult r = validateField(key, value);
            validated.put(key, r.valid() ? r.value() : null);
        }
        // preserve fields not listed in extra_args
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!validated.containsKey(e.getKey())) {
                validated.put(e.getKey(), e.getValue());
            }
        }
        return wrapArgs(validated);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> argsOf(Map<String, Object> context) {
        if (context == null) {
            return new LinkedHashMap<>();
        }
        Object a = context.get("arguments");
        if (a instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    /**
     * ValidationResult.
     *
     * @param valid valid
     * @param value value
     */

    public record ValidationResult(boolean valid, Object value) {

        /**
         * ok.
         *
         * @param value value
         * @return result
         */

        public static ValidationResult ok(Object value) {
        return new ValidationResult(true, value);
    }

        /**
         * fail.
         *
         * @return result
         */

        public static ValidationResult fail() {
            return new ValidationResult(false, null);
        }
    }
}
