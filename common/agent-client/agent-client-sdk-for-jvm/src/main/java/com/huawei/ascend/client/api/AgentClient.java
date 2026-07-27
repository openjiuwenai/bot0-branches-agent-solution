/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;

/**
 * agent-client SDK 的主门面（FEAT-006 / FEAT-007）。
 *
 * <p>职责边界：本 SDK 只做"客户端侧"的调用编排、事件投影与本地工具驱动，
 * <b>不拥有</b>服务端 Task 的权威状态。所有查询返回的都是投影快照。
 *
 * <p>本地工具的注册见 {@link #tools()}；工具在默认情况下不对服务端暴露，
 * 需通过会话级 {@link #exposeInConversation} 或调用级
 * {@link InvocationRequest.Builder#exposure} 显式授权后，才会以 ToolView 形式上报。
 *
 * @since 2026-07-27
 */
public interface AgentClient extends AutoCloseable {

    /**
     * 发起一次调用，立即返回句柄；网络交互在后台进行。
     *
     * <p>本迭代（730）仅交付 {@link InvocationMode#STREAMING}；传入 {@code BLOCKING}/{@code ASYNC}
     * 会抛出 {@link UnsupportedOperationException}（{@code UNSUPPORTED_MODE}）。
     *
     * @param request 调用请求
     * @return 调用句柄
     */
    InvocationCall invoke(InvocationRequest request);

    /**
     * 把用户补充输入续传给处于 INPUT_REQUIRED（非 client_tool）的既有调用，返回续传句柄。
     *
     * @param request 续传请求
     * @return 续传句柄
     */
    InvocationCall continueInput(ContinueInputRequest request);

    /**
     * 声明某个会话级别的工具暴露策略，对该会话后续的所有调用生效。
     *
     * @param conversationId 会话标识
     * @param policy 暴露策略
     */
    void exposeInConversation(String conversationId, ToolExposurePolicy policy);

    /**
     * 本地工具注册表（FEAT-007 SPI 入口）。
     *
     * @return 工具注册表
     */
    LocalToolRegistry tools();

    @Override
    void close();
}
