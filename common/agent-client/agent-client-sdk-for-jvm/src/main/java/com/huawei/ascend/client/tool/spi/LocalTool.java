/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * 本地工具的标准化 SPI（FEAT-007）。业务实现该接口把设备/本地能力暴露给远端智能体驱动调用。
 *
 * <p>约定：{@link #execute} 应返回单一最终 {@link ToolExecutionRecord}（成功或结构化失败），
 * 不抛出受检异常传播实现细节；SDK 会对超时、去重、审批做统一编排。
 *
 * @since 2026-07-27
 */
@FunctionalInterface
public interface LocalTool {
    /**
     * 执行结果 future。
     *
     * @param invocation ToolInvocation
     * @param context ToolExecutionContext
     * @return 执行结果 future
     */
    CompletionStage<ToolExecutionRecord> execute(ToolInvocation invocation, ToolExecutionContext context);

    /**
     * 便捷工厂：用同步 lambda 构造工具实现。
     *
     * @param descriptor LocalToolDescriptor
     * @param fn ToolExecutionRecord>
     * @return 已注册工具
     */
    static Registered of(LocalToolDescriptor descriptor,
                         BiFunction<ToolInvocation, ToolExecutionContext, ToolExecutionRecord> fn) {
        LocalTool tool = (inv, ctx) -> CompletableFuture.completedFuture(fn.apply(inv, ctx));
        return new Registered(descriptor, tool);
    }

    /**
     * 便捷工厂：用异步实现构造工具。
     *
     * @param descriptor LocalToolDescriptor
     * @param tool LocalTool
     * @return 已注册异步工具
     */
    static Registered ofAsync(LocalToolDescriptor descriptor, LocalTool tool) {
        return new Registered(descriptor, tool);
    }

    /**
     * 描述符 + 实现的绑定，用于注册。
     */
    record Registered(LocalToolDescriptor descriptor, LocalTool tool) {
        // 仅规范构造器，无额外成员。
    }
}