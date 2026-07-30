/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

/**
 * 验证过程的进度回调（CLI 打印 / Web UI 推送共用）。
 *
 * @since 2026-07-27
 */
@FunctionalInterface
interface VerificationProgress {
    /**
     * 处理一个进度事件。
     *
     * @param event 进度事件
     */
    void onEvent(Event event);

    record Event(Kind kind, String scenarioId, String message, Boolean ok) {
        /**
         * info 事件。
         *
         * @param scenarioId String
         * @param message String
         * @return info 事件
         */
        static Event info(String scenarioId, String message) {
            return new Event(Kind.INFO, scenarioId, message, null);
        }

        /**
         * 场景开始事件。
         *
         * @param scenarioId String
         * @param title String
         * @return 场景开始事件
         */
        static Event scenarioStart(String scenarioId, String title) {
            return new Event(Kind.SCENARIO_START, scenarioId, title, null);
        }

        /**
         * 场景结束事件。
         *
         * @param scenarioId String
         * @param ok boolean
         * @return 场景结束事件
         */
        static Event scenarioEnd(String scenarioId, boolean ok) {
            return new Event(Kind.SCENARIO_END, scenarioId, ok ? "passed" : "failed", ok);
        }

        /**
         * 准入决策。
         *
         * @param scenarioId String
         * @param ok boolean
         * @param message String
         * @return 准入决策
         */
        static Event check(String scenarioId, boolean ok, String message) {
            return new Event(Kind.CHECK, scenarioId, message, ok);
        }

        /**
         * 运行开始事件。
         *
         * @param gatewayUrl String
         * @return 运行开始事件
         */
        static Event runStart(String gatewayUrl) {
            return new Event(Kind.RUN_START, null, gatewayUrl, null);
        }

        /**
         * 运行结束事件。
         *
         * @param ok boolean
         * @param summary String
         * @return 运行结束事件
         */
        static Event runEnd(boolean ok, String summary) {
            return new Event(Kind.RUN_END, null, summary, ok);
        }
    }

    /**
     * 进度事件类型。
     */
    enum Kind {
        RUN_START,
        SCENARIO_START,
        INFO,
        CHECK,
        SCENARIO_END,
        RUN_END
    }
}
