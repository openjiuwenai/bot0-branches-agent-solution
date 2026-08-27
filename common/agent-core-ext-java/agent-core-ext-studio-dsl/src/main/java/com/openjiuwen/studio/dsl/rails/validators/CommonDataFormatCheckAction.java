/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.validators;

import com.openjiuwen.studio.dsl.rails.ActionConfig;
import com.openjiuwen.studio.dsl.rails.ValidateAction;

import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * common_data_format_check.
 *
 * @since 2026-08-25
 */

public final class CommonDataFormatCheckAction extends ValidateAction {
    private static final Map<String, String> FORMAT_NAME_MAP = Map.ofEntries(
            Map.entry("银行卡号", "bank_card"),
            Map.entry("电话号码", "phone"),
            Map.entry("护照号码", "passport"),
            Map.entry("图片base64", "image_base64"),
            Map.entry("邮箱", "email"),
            Map.entry("IP地址", "ip_address"),
            Map.entry("MAC地址", "mac_address"),
            Map.entry("邮政编码", "postal_code"),
            Map.entry("UUID", "uuid"),
            Map.entry("车牌号", "license_plate"));

    private static final Map<String, Pattern> PATTERNS = Map.ofEntries(
            Map.entry("bank_card", Pattern.compile("^[1-9]\\d{9,29}$")),
            Map.entry("phone", Pattern.compile("^1[3-9]\\d{9}$")),
            Map.entry("phone_with_country_code", Pattern.compile("^\\+?[1-9]\\d{1,14}$")),
            Map.entry("passport", Pattern.compile("^[A-Z0-9]{6,9}$")),
            Map.entry("url", Pattern.compile("^https?://[\\w\\-]+(\\.[\\w\\-]+)+[\\w\\-.,@?^=%&:/~+#]*$")),
            Map.entry("email", Pattern.compile("^[\\w.\\-]+@[\\w.\\-]+\\.\\w+$")),
            Map.entry("ip_address", Pattern.compile("^(\\d{1,3}\\.) {3}\\d{1,3}$")),
            Map.entry("mac_address", Pattern.compile("^([0-9A-Fa-f]{2}[:-]) {5}([0-9A-Fa-f]{2})$")),
            Map.entry("postal_code", Pattern.compile("^\\d{6}$")),
            Map.entry(
                    "uuid",
                    Pattern.compile(
                            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                            Pattern.CASE_INSENSITIVE)),
            Map.entry(
                    "license_plate",
                    Pattern.compile(
                            "^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-Z0-9]{4,5}[A-Z0-9挂学警港澳]$")));

    /**
     * CommonDataFormatCheckAction.
     *
     * @param config config
     */

    public CommonDataFormatCheckAction(ActionConfig config) {
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
        Object formatTypeObj = extraArgs.get(fieldName);
        if (formatTypeObj == null) {
            return ValidationResult.ok(value);
        }
        String formatType = FORMAT_NAME_MAP.getOrDefault(String.valueOf(formatTypeObj), String.valueOf(formatTypeObj));
        if ("image_base64".equals(formatType)) {
            return validateBase64Image(value);
        }
        Pattern p = PATTERNS.get(formatType);
        if (p == null) {
            return ValidationResult.ok(value);
        }
        String s = value instanceof String str ? str : String.valueOf(value);
        return p.matcher(s).matches() ? ValidationResult.ok(s) : ValidationResult.fail();
    }

    private static ValidationResult validateBase64Image(Object value) {
        if (!(value instanceof String s)) {
        return ValidationResult.fail();
    }
        String raw = s.contains(",") ? s.substring(s.indexOf(',') + 1) : s;
        try {
            Base64.getDecoder().decode(raw);
            return ValidationResult.ok(s);
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail();
        }
    }
}
