/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

/**
 * 验证过程的进度回调（CLI 打印 / Web UI 推送共用）。
 */
@FunctionalInterface
interface VerificationProgress {

    void onEvent(Event event);

    record Event(Kind kind, String scenarioId, String message, Boolean ok) {

        static Event info(String scenarioId, String message) {
            return new Event(Kind.INFO, scenarioId, message, null);
        }

        static Event scenarioStart(String scenarioId, String title) {
            return new Event(Kind.SCENARIO_START, scenarioId, title, null);
        }

        static Event scenarioEnd(String scenarioId, boolean ok) {
            return new Event(Kind.SCENARIO_END, scenarioId, ok ? "passed" : "failed", ok);
        }

        static Event check(String scenarioId, boolean ok, String message) {
            return new Event(Kind.CHECK, scenarioId, message, ok);
        }

        static Event runStart(String gatewayUrl) {
            return new Event(Kind.RUN_START, null, gatewayUrl, null);
        }

        static Event runEnd(boolean ok, String summary) {
            return new Event(Kind.RUN_END, null, summary, ok);
        }
    }

    enum Kind {
        RUN_START,
        SCENARIO_START,
        INFO,
        CHECK,
        SCENARIO_END,
        RUN_END
    }
}
