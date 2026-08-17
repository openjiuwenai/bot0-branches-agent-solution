/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import com.openjiuwen.client.api.calltree.CallTreeSnapshot;

import java.util.Optional;

/**
 * 某次调用在某一时刻的状态快照投影（FEAT-006 §L-07/12）。
 *
 * <p>由 {@link AgentClient#getInvocation} 与 {@link InvocationCall#completion()} 返回，
 * 反映"客户端此刻观测到的"状态。它<b>不是</b>服务端权威状态，可能滞后。
 * 可空字段（diagnosticTaskRef/pendingToolCall/outputText/errorCode/message/recovery）以 null 表示不适用。
 *
 * @param invocationRef 调用句柄
 * @param state 任务状态
 * @param terminal 是否终态
 * @param diagnosticTaskRef 诊断任务引用
 * @param pendingToolCall 待处理工具调用
 * @param outputText 输出文本
 * @param errorCode 错误码
 * @param message 消息文本
 * @param recovery 恢复线索；仅在进展不确定时非空
 * @since 2026-07-27
 */
public record InvocationSnapshot(
        String invocationRef,
        TaskState state,
        boolean terminal,
        String diagnosticTaskRef,
        InvocationEvent.ToolCall pendingToolCall,
        String outputText,
        String errorCode,
        String message,
        Recovery recovery,
        CallTreeSnapshot callTree) {
    /**
     * 无恢复线索的快照（正常路径）。
     *
     * @param invocationRef 调用句柄
     * @param state 任务状态
     * @param terminal 是否终态
     * @param diagnosticTaskRef 诊断任务引用
     * @param pendingToolCall 待处理工具调用
     * @param outputText 输出文本
     * @param errorCode 错误码
     * @param message 消息文本
     */
    public InvocationSnapshot(String invocationRef, TaskState state, boolean terminal,
                              String diagnosticTaskRef, InvocationEvent.ToolCall pendingToolCall,
                              String outputText, String errorCode, String message) {
        this(invocationRef, state, terminal, diagnosticTaskRef, pendingToolCall,
                outputText, errorCode, message, null, null);
    }

    /**
     * 保留原九参数构造器，兼容已有源码与编译产物。
     *
     * @param invocationRef 调用句柄
     * @param state 任务状态
     * @param terminal 是否终态
     * @param diagnosticTaskRef 诊断任务引用
     * @param pendingToolCall 待处理工具调用
     * @param outputText 输出文本
     * @param errorCode 错误码
     * @param message 消息文本
     * @param recovery 恢复线索
     */
    public InvocationSnapshot(String invocationRef, TaskState state, boolean terminal,
                              String diagnosticTaskRef, InvocationEvent.ToolCall pendingToolCall,
                              String outputText, String errorCode, String message, Recovery recovery) {
        this(invocationRef, state, terminal, diagnosticTaskRef, pendingToolCall,
                outputText, errorCode, message, recovery, null);
    }

    /**
     * 构造一个"未知"状态的快照（用于查询不到调用时）。
     *
     * @param invocationRef 调用句柄
     * @return 未知状态快照
     */
    public static InvocationSnapshot unknown(String invocationRef) {
        return new InvocationSnapshot(invocationRef, TaskState.UNKNOWN, false, null, null, null, null, null);
    }

    /**
     * 恢复线索；仅在进展不确定时非空。
     *
     * @return 恢复线索
     */
    public Optional<Recovery> maybeRecovery() {
        return Optional.ofNullable(recovery);
    }

    /**
     * 返回调用树，旧 Transport 可能为空。
     *
     * @return 调用树
     */
    public Optional<CallTreeSnapshot> maybeCallTree() {
        return Optional.ofNullable(callTree);
    }

    /**
     * 进展不确定时的恢复线索（FEAT-006 §5.1.4，L2 Feat-Func-006 §5.2 b2）。
     *
     * <p>SDK 在流中断且无法通过 {@code GetTask} 确定状态时附带本对象，说明"下一步该怎么做"。
     * 它<b>不</b>代表失败，调用方不应据此把调用判为失败。
     *
     * @param reason 不确定的原因（诊断用）
     * @param conversationId 会话标识，重发时须复用
     * @param idempotencyKey 幂等键；{@link Action#RETRY_CREATE_SAME_KEY} 时必须原样复用
     * @param suggestedAction 建议动作
     */
    public record Recovery(String reason, String conversationId, String idempotencyKey,
            Action suggestedAction) {
        /**
         * 建议调用方采取的恢复动作。
         */
        public enum Action {
            /** 无需恢复。 */
            NONE,
            /**
             * Task 已创建但进展未知：稍后调用 {@link AgentClient#getInvocation} 再次确认。
             * <b>不要</b>重新发起调用，否则会产生重复 Task。
             */
            QUERY_INVOCATION,
            /**
             * 创建请求未被确认：以<b>同一幂等键与逐字节相同的正文</b>重发创建。
             * 网关会命中幂等回放取回原 Task，不会产生重复 Task（L2 Feat-Func-006 §5.4）。
             */
            RETRY_CREATE_SAME_KEY,
            /** Runtime 创建结果未知且不安全重发，需要业务侧人工核对。 */
            MANUAL_RECONCILIATION
        }
    }
}
