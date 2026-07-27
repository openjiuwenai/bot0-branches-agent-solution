/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

/**
 * 本地工具执行的最终结果记录（FEAT-007 §L-08/09）。
 *
 * <p>关键约束：每次工具调用<b>只产生一个最终结果</b>（成功或结构化失败），
 * SDK 据此向服务端提交唯一续传结果，不产生中间态外泄。
 *
 * <p>{@code payload} 是结构化结果对象（成功时），SDK 会将其渲染为服务端可消费的 observation 文本；
 * {@code payloadRef} 用于大负载的引用式返回（可选）。
 *
 * @since 2026-07-27
 */
public record ToolExecutionRecord(
        String toolCallId,
        Outcome outcome,
        Object payload,
        String payloadRef,
        String errorCode,
        String message) {

    /**
     * 工具执行结果分类。
     *
     * @since 2026-07-27
     */
    public enum Outcome {
        /** 成功产生结果。 */
        OK,
        /** 工具执行内部错误（结构化失败，可续传给服务端）。 */
        ERROR,
        /** 被本地治理拒绝（策略/审批）。 */
        REJECTED,
        /** 超出执行期限。 */
        TIMEOUT
    }

    /**
     * 是否执行成功。
     *
     * @return 成功返回 true
     */
    public boolean isOk() {
        return outcome == Outcome.OK;
    }

    /**
     * 成功结果工厂。
     *
     * @param toolCallId 工具调用标识
     * @param payload 结构化结果
     * @return 成功结果记录
     */
    public static ToolExecutionRecord ok(String toolCallId, Object payload) {
        return new ToolExecutionRecord(toolCallId, Outcome.OK, payload, null, null, null);
    }

    /**
     * 成功结果工厂（带大负载引用）。
     *
     * @param toolCallId 工具调用标识
     * @param payload 结构化结果
     * @param payloadRef 大负载引用
     * @return 成功结果记录
     */
    public static ToolExecutionRecord okRef(String toolCallId, Object payload, String payloadRef) {
        return new ToolExecutionRecord(toolCallId, Outcome.OK, payload, payloadRef, null, null);
    }

    /**
     * 错误结果工厂。
     *
     * @param toolCallId 工具调用标识
     * @param errorCode 错误码
     * @param message 错误信息
     * @return 错误结果记录
     */
    public static ToolExecutionRecord error(String toolCallId, String errorCode, String message) {
        return new ToolExecutionRecord(toolCallId, Outcome.ERROR, null, null, errorCode, message);
    }

    /**
     * 被拒绝结果工厂。
     *
     * @param toolCallId 工具调用标识
     * @param errorCode 错误码
     * @param reason 拒绝原因
     * @return 被拒绝结果记录
     */
    public static ToolExecutionRecord rejected(String toolCallId, String errorCode, String reason) {
        return new ToolExecutionRecord(toolCallId, Outcome.REJECTED, null, null, errorCode, reason);
    }

    /**
     * 超时结果工厂。
     *
     * @param toolCallId 工具调用标识
     * @param errorCode 错误码
     * @param message 错误信息
     * @return 超时结果记录
     */
    public static ToolExecutionRecord timeout(String toolCallId, String errorCode, String message) {
        return new ToolExecutionRecord(toolCallId, Outcome.TIMEOUT, null, null, errorCode, message);
    }

    /**
     * 便捷工厂：errorCode 默认为 {@code timeout}（对齐 007 §5.3 闭集）。
     *
     * @return 便捷工厂：errorCode 默认为 {@code timeout}（对齐 007 §5.3 闭集）。
     */
    public static ToolExecutionRecord timeout(String toolCallId, String message) {
        return timeout(toolCallId, "timeout", message);
    }
}
