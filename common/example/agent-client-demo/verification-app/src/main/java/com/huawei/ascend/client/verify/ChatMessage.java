/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

import java.util.Map;

/**
 * 推给前端 SSE 的对话消息（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>一条消息 = 一个对话气泡或一条状态/断言。前端按 {@link #type} 渲染：
 * <ul>
 * <li>{@code user} —— 用户 query（右气泡）。</li>
 * <li>{@code assistant_delta} / {@code assistant_final} —— 智能体回复（左气泡，流式增量或最终）。</li>
 * <li>{@code tool_call} —— 工具调用卡片（工具名 + 入参，可折叠）。</li>
 * <li>{@code tool_result} —— 工具结果卡片（payload JSON + outcome 徽标）。</li>
 * <li>{@code status} —— 状态标签（ACCEPTED/WORKING/INPUT_REQUIRED/...）。</li>
 * <li>{@code assertion} —— 断言（消息尾简标 + 侧栏详情）。</li>
 * <li>{@code error} —— 错误（errorCode + message）。</li>
 * <li>{@code info} —— 普通提示（审批通过、BLOCKING 拒绝说明等）。</li>
 * <li>{@code scenario_start} / {@code scenario_end} —— 场景边界标记。</li>
 * <li>{@code session_created} —— 新会话创建。</li>
 * </ul>
 */
record ChatMessage(
        String type,
        String sessionId,
        String invocationRef,
        String toolCallId,
        String text,
        String state,
        String detail,
        Map<String, Object> arguments,
        Object payload,
        String outcome,
        String errorCode,
        String message,
        Boolean ok,
        String scenarioId,
        String label) {
    // ---- 工厂方法 ----

    /**
     * user 消息。
     *
     * @param sessionId String
     * @param invocationRef String
     * @param text String
     * @return user 消息
     */

    static ChatMessage user(String sessionId, String invocationRef, String text) {
        return new ChatMessage("user", sessionId, invocationRef, null, text,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * assistant 增量消息。
     *
     * @param sessionId String
     * @param invocationRef String
     * @param text String
     * @return assistant 增量消息
     */

    static ChatMessage assistantDelta(String sessionId, String invocationRef, String text) {
        return new ChatMessage("assistant_delta", sessionId, invocationRef, null, text,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * assistant 终态消息。
     *
     * @param sessionId String
     * @param invocationRef String
     * @param text String
     * @return assistant 终态消息
     */

    static ChatMessage assistantFinal(String sessionId, String invocationRef, String text) {
        return new ChatMessage("assistant_final", sessionId, invocationRef, null, text,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * toolCall 消息。
     *
     * @param sessionId String
     * @param toolCallId String
     * @param toolName String
     * @param arguments Object>
     * @return toolCall 消息
     */

    static ChatMessage toolCall(String sessionId, String toolCallId, String toolName,
                                Map<String, Object> arguments) {
        return new ChatMessage("tool_call", sessionId, null, toolCallId, toolName,
                null, null, arguments, null, null, null, null, null, null, null);
    }

    /**
     * toolResult 消息。
     *
     * @param sessionId String
     * @param toolCallId String
     * @param detail ToolResultDetail
     * @return toolResult 消息
     */

    static ChatMessage toolResult(String sessionId, String toolCallId, ToolResultDetail detail) {
        return new ChatMessage("tool_result", sessionId, null, toolCallId, null,
                null, null, null, detail.payload(), detail.outcome(),
                detail.errorCode(), detail.message(), null, null, null);
    }

    /**
     * 工具结果明细（聚合 outcome/payload/errorCode/message，控制 toolResult 参数数量，G.MET.01）。
     */
    record ToolResultDetail(String outcome, Object payload, String errorCode, String message) {
        /**
         * 已注册工具。
         *
         * @param outcome String
         * @param payload Object
         * @param errorCode String
         * @param message String
         * @return 已注册工具
         */
        static ToolResultDetail of(String outcome, Object payload, String errorCode, String message) {
            return new ToolResultDetail(outcome, payload, errorCode, message);
        }
    }

    /**
     * status 消息。
     *
     * @param sessionId String
     * @param invocationRef String
     * @param state String
     * @param detail String
     * @return status 消息
     */

    static ChatMessage status(String sessionId, String invocationRef, String state, String detail) {
        return new ChatMessage("status", sessionId, invocationRef, null, null,
                state, detail, null, null, null, null, null, null, null, null);
    }

    /**
     * assertion 消息。
     *
     * @param sessionId String
     * @param scenarioId String
     * @param ok boolean
     * @param message String
     * @return assertion 消息
     */

    static ChatMessage assertion(String sessionId, String scenarioId, boolean ok, String message) {
        return new ChatMessage("assertion", sessionId, null, null, null,
                null, null, null, null, null, null, message, ok, scenarioId, null);
    }

    /**
     * error 消息。
     *
     * @param sessionId String
     * @param invocationRef String
     * @param errorCode String
     * @param message String
     * @return error 消息
     */

    static ChatMessage error(String sessionId, String invocationRef, String errorCode, String message) {
        return new ChatMessage("error", sessionId, invocationRef, null, null,
                null, null, null, null, null, errorCode, message, null, null, null);
    }

    /**
     * info 事件。
     *
     * @param sessionId String
     * @param message String
     * @return info 事件
     */

    static ChatMessage info(String sessionId, String message) {
        return new ChatMessage("info", sessionId, null, null, null,
                null, null, null, null, null, null, message, null, null, null);
    }

    /**
     * 场景开始事件。
     *
     * @param sessionId String
     * @param scenarioId String
     * @param label String
     * @return 场景开始事件
     */

    static ChatMessage scenarioStart(String sessionId, String scenarioId, String label) {
        return new ChatMessage("scenario_start", sessionId, null, null, null,
                null, null, null, null, null, null, null, null, scenarioId, label);
    }

    /**
     * 场景结束事件。
     *
     * @param sessionId String
     * @param scenarioId String
     * @param ok boolean
     * @return 场景结束事件
     */

    static ChatMessage scenarioEnd(String sessionId, String scenarioId, boolean ok) {
        return new ChatMessage("scenario_end", sessionId, null, null, null,
                null, null, null, null, null, null, null, ok, scenarioId, null);
    }

    /**
     * sessionCreated 消息。
     *
     * @param sessionId String
     * @param label String
     * @return sessionCreated 消息
     */

    static ChatMessage sessionCreated(String sessionId, String label) {
        return new ChatMessage("session_created", sessionId, null, null, null,
                null, null, null, null, null, null, null, null, null, label);
    }
}
