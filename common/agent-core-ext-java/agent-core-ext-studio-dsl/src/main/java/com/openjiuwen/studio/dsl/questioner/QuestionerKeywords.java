/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import java.util.List;

/**
 * User intent keywords — Python {@code USER_CONFIRM_INFO} / {@code USER_BREAK_INFO}.
 *
 * @since 2026-08-26
 */
final class QuestionerKeywords {
    static final List<String> USER_CONFIRM_INFO = List.of("%确认%", "确认", "没错", "正确");
    static final List<String> USER_BREAK_INFO = List.of("%跳出%", "结束", "意图结束", "跳出", "退出");

    static final String MSG_BREAK = "已退出提问";
    static final String MSG_CONFIRMED = "已确认参数";

    private QuestionerKeywords() {}

    static boolean matchesConfirm(String userResponse) {
        return containsAny(userResponse, USER_CONFIRM_INFO);
    }

    static boolean matchesBreak(String userResponse) {
        return containsAny(userResponse, USER_BREAK_INFO);
    }

    private static boolean containsAny(String userResponse, List<String> keywords) {
        if (userResponse == null || userResponse.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (userResponse.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
