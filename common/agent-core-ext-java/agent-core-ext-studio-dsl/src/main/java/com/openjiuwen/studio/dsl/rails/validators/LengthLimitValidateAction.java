/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.validators;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

/**
 * length_limit_validate.
 *
 * @since 2026-08-25
 */

public final class LengthLimitValidateAction extends ValidateAction {

    /**
     * LengthLimitValidateAction.
     *
     * @param config config
     */

    public LengthLimitValidateAction(ActionConfig config) {
        super(config);
    }

    /**
     * validateField.
     *
     * @param fieldName fieldName
     * @param value value
     * @return result
     * @since 0.1.0
     */

    @Override
    protected ValidationResult validateField(String fieldName, Object value) {
        Object max = extraArgs.get(fieldName);
        if (max == null) {
            return ValidationResult.ok(value);
        }
        int maxLength;
        try {
            maxLength = max instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(max));
        } catch (NumberFormatException e) {
            return ValidationResult.ok(value);
        }
        String s = value instanceof String str ? str : String.valueOf(value);
        return s.length() <= maxLength ? ValidationResult.ok(s) : ValidationResult.fail();
    }
}
