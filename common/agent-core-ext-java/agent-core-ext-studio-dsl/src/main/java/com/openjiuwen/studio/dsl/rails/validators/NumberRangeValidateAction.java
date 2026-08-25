/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.validators;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

import java.util.List;

/**
 * number_range_validate.
 *
 * @since 2026-08-25
 */
public final class NumberRangeValidateAction extends ValidateAction {
    /**
     * NumberRangeValidateAction.
     *
     * @param config config
     */
    public NumberRangeValidateAction(ActionConfig config) {
        super(config);
    }

    @Override
    protected ValidationResult validateField(String fieldName, Object value) {
        Object rangeSpec = extraArgs.get(fieldName);
        if (!(rangeSpec instanceof List<?> list) || list.size() != 2) {
            return ValidationResult.ok(value);
        }
        double min;
        double max;
        try {
            min = toDouble(list.get(0));
            max = toDouble(list.get(1));
            double num = toDouble(value);
            if (num < min || num > max) {
                return ValidationResult.fail();
            }
            if (value instanceof Integer || (list.get(0) instanceof Integer && list.get(1) instanceof Integer)) {
                return ValidationResult.ok((long) num);
            }
            return ValidationResult.ok(num);
        } catch (NumberFormatException e) {
            return ValidationResult.fail();
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(v));
    }
}
