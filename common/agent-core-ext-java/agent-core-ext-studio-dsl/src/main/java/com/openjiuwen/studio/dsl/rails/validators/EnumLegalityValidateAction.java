/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.validators;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * enum_legality_validate — value in enum AND substring of user_input.
 *
 * @since 2026-08-25
 */
public final class EnumLegalityValidateAction extends ValidateAction {
    /**
     * EnumLegalityValidateAction.
     *
     * @param config config
     */
    public EnumLegalityValidateAction(ActionConfig config) {
        super(config);
    }

    @Override
    protected ValidationResult validateField(String fieldName, Object value) {
        return ValidationResult.ok(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> context) {
        Map<String, Object> args = ValidateAction.argsOf(context);
        String userInput = context == null ? "" : String.valueOf(context.getOrDefault("user_input", ""));
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : extraArgs.entrySet()) {
            String field = e.getKey();
            Object value = args.get(field);
            if (value == null) {
                validated.put(field, null);
                continue;
            }
            Object allowed = e.getValue();
            if (!(allowed instanceof List<?> list)) {
                validated.put(field, value);
                continue;
            }
            if (!list.contains(value)) {
                validated.put(field, null);
                continue;
            }
            if (value instanceof String s && !userInput.contains(s)) {
                validated.put(field, null);
                continue;
            }
            validated.put(field, value);
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!validated.containsKey(e.getKey())) {
                validated.put(e.getKey(), e.getValue());
            }
        }
        return wrapArgs(validated);
    }
}
