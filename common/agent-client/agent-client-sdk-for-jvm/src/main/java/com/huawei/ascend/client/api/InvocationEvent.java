/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 调用过程中的标准化反馈事件（FEAT-006 §L-06、L2 Feat-Func-006 §2.3）。
 *
 * <p>所有事件都以客户端调用句柄 {@code invocationRef} 归集，业务无需感知服务端 {@code taskId}。
 * 使用 sealed + record，便于 {@code switch} 模式匹配穷尽处理。
 *
 * @since 2026-07-27
 */
public sealed interface InvocationEvent
        permits InvocationEvent.Accepted,
                InvocationEvent.StatusChanged,
                InvocationEvent.ContentDelta,
                InvocationEvent.InputRequired,
                InvocationEvent.Completed,
                InvocationEvent.Failed {
    /**
     * 客户端拥有的调用句柄。
     *
     * @return 调用句柄
     */
    String invocationRef();

    /**
     * 调用已被受理。{@code diagnosticTaskRef} 仅用于诊断/日志，非业务主键。
     *
     * @param invocationRef 调用句柄
     * @param diagnosticTaskRef 诊断任务引用
     * @param conversationId 会话标识
     */
    record Accepted(String invocationRef, String diagnosticTaskRef, String conversationId)
            implements InvocationEvent {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 状态投影发生变化。
     *
     * @param invocationRef 调用句柄
     * @param state 任务状态
     * @param terminal 是否终态
     */
    record StatusChanged(String invocationRef, TaskState state, boolean terminal)
            implements InvocationEvent {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 增量输出内容（流式）。
     *
     * @param invocationRef 调用句柄
     * @param text 文本内容
     */
    record ContentDelta(String invocationRef, String text) implements InvocationEvent {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 需要客户端提供输入。
     * <ul>
     * <li>{@code toolCall} 存在 —— 属于 client_tool 类型，SDK 会自动就地执行并续传（FEAT-007）。</li>
     * <li>{@code toolCall} 为空 —— 属于需要用户补充输入，业务应调用
     * {@link AgentClient#continueInput}。</li>
     * </ul>
     *
     * @param invocationRef 调用句柄
     * @param toolCall 工具调用
     * @param prompt 提示文本
     */
    record InputRequired(String invocationRef, ToolCall toolCall, String prompt)
            implements InvocationEvent {
        /**
         * {@code toolCall} 存在与否由调用方判空；此处提供便捷包装。
         *
         * @return {@code toolCall} 存在与否由调用方判空；此处提供便捷包装。
         */
        public Optional<ToolCall> maybeToolCall() {
            return Optional.ofNullable(toolCall);
        }
    }

    /**
     * 调用完成（终态）。
     *
     * @param invocationRef 调用句柄
     * @param outputText 输出文本
     */
    record Completed(String invocationRef, String outputText) implements InvocationEvent {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 调用失败（终态）。{@code errorCode} 为标准化错误分类。
     *
     * @param invocationRef 调用句柄
     * @param errorCode 错误码
     * @param message 消息文本
     */
    record Failed(String invocationRef, String errorCode, String message) implements InvocationEvent {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 服务端请求的一次 client 工具调用意图（对应 runtime {@code _interrupt} 投影，见 Feat-Func-009）。
     * {@code toolName} 即客户端上报 ToolView 时使用的工具名（等于 toolId）。
     *
     * @param toolCallId 工具调用标识
     * @param toolName 工具名
     * @param arguments 工具参数
     * @param deadline 截止时间
     */
    record ToolCall(String toolCallId, String toolName, Map<String, Object> arguments, Duration deadline) {
        public ToolCall {
            arguments = (arguments == null)
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }
}
