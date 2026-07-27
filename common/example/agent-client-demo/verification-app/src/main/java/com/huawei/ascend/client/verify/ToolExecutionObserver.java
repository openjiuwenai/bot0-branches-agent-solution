/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;
import com.huawei.ascend.client.tool.spi.ToolInvocation;

import java.util.Map;

/**
 * 本地工具执行观察者（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>SDK 当前对业务侧隐藏 client_tool 的 {@code InputRequired}（自动消费）且不外泄
 * {@link ToolExecutionRecord}（仅内部续传用）。为了让对话式前端能展示"执行了哪个工具、入参是什么、结果是什么"，
 * 在 {@link DemoTools} 的工具 lambda 返回前调用本观察者，把完整信息侧信道推给
 * {@link ConversationDriver}，再经 SSE 转发给浏览器。
 *
 * <p>SDK 零改动：本接口完全属于 verification-app，不依赖 SDK 任何内部类。
 */
@FunctionalInterface
interface ToolExecutionObserver {
    /**
     * 工具执行完成时回调。
     *
     * @param invocation 工具调用入参（含 toolId / arguments / toolCallId）
     * @param record     工具执行结果（含 payload / outcome）
     */
    void onExecuted(ToolInvocation invocation, ToolExecutionRecord record);

    /**
     * 空实现观察者。
     *
     * @return 空实现观察者
     */
    static ToolExecutionObserver noop() {
        return (invocation, record) -> {
            // 空实现：CLI 全量断言模式不需要推前端。
        };
    }

    /** 记录一次工具执行的不可变快照，供 SSE 序列化。 */
    record Snapshot(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            ToolExecutionRecord.Outcome outcome,
            Object payload,
            String errorCode,
            String message) {
        /**
         * 已注册工具。
         *
         * @param invocation ToolInvocation
         * @param record ToolExecutionRecord
         * @return 已注册工具
         */
        static Snapshot of(ToolInvocation invocation, ToolExecutionRecord record) {
            return new Snapshot(
                    invocation.toolCallId(),
                    invocation.toolId(),
                    invocation.arguments(),
                    record.outcome(),
                    record.payload(),
                    record.errorCode(),
                    record.message());
        }
    }
}
