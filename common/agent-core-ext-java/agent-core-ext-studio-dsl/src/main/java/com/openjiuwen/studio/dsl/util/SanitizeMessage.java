/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Mask common secrets in error / stream messages
 * (Python {@code extension/workflow_node/sub_workflow.sanitize_message}).
 *
 * @since 2026-08-26
 */

public final class SanitizeMessage {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(secret key:\\s*)(\\S+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(password:\\s*)(\\S+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(access token:\\s*)(\\S+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(api key:\\s*)(\\S+)", Pattern.CASE_INSENSITIVE));

    private SanitizeMessage() {}

    /**
     * Sanitize sensitive fragments; null-safe (returns empty string for null).
     *
     * @param message raw message
     * @return sanitized message
     */

    public static String sanitize(String message) {
        if (message == null) {
        return "";
    }
        String out = message;
        for (Pattern pattern : PATTERNS) {
            out = pattern.matcher(out).replaceAll("$1***");
        }
        return out;
    }
}
