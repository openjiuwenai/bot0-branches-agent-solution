/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import com.openjiuwen.client.tool.spi.LocalToolRegistry;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;

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
     * <p>单个 Client 生命周期内准入的不同 conversationId 数受
     * {@link AgentClients.Builder#maxDistinctConversations(int)} 约束，默认最多 5 个。
     * 超限时在发出任何网络请求前同步抛出 {@link ConversationLimitExceededException}。
     *
     * <p>三种调用模式共用同一句柄，差异只在传输承载与调用方的消费方式：
     * <ul>
     * <li>{@link InvocationMode#STREAMING} —— 订阅 {@link InvocationCall#events()} 增量消费。</li>
     * <li>{@link InvocationMode#BLOCKING} —— 忽略事件流，直接等 {@link InvocationCall#completion()}。
     * 由传输层走带 {@code returnImmediately=false} 的网关 unary 接口获取结果，<b>不</b>是把流式结果在本地聚合。</li>
     * <li>{@link InvocationMode#ASYNC} —— 拿到 {@link InvocationCall#accepted()} 即返回，
     * Gateway 与 Runtime 在受理后均不自动轮询；业务通过 {@link #getInvocation} 按需查询，
     * 查询到终态或根 {@code INPUT_REQUIRED} 时驱动原调用结算。</li>
     * </ul>
     *
     * @param request 调用请求
     * @return 调用句柄
     */
    InvocationCall invoke(InvocationRequest request);

    /**
     * 把用户补充输入续传给处于 INPUT_REQUIRED（非 client_tool）的既有调用，返回续传句柄。
     *
     * <p>这会产生一个业务可见的<b>新</b> invocation，关联到同一个服务端 Task。
     * 端侧工具结果续跑<b>不</b>走本方法——那是 SDK 内部自动完成的，业务不感知。
     *
     * @param request 续传请求
     * @return 续传句柄
     */
    InvocationCall continueInput(ContinueInputRequest request);

    /**
     * 查询某次调用的状态快照投影（wire 方法 {@code GetTask}）。
     *
     * <p>返回的是<b>投影</b>而非本地缓存：SDK 会向网关查询权威状态。适用于：
     * ASYNC 模式一次性主动检查、事件流投递 {@link InvocationEvent.ProgressUncertain} 后确认真实状态、
     * 或跨请求校正本地滞后状态。
     *
     * <p>本地无该 {@code invocationRef} 映射（如未建立 taskRef、或已被回收）时返回
     * {@link InvocationSnapshot#unknown}，不抛异常。
     *
     * @param invocationRef 调用句柄
     * @return 状态快照
     */
    java.util.concurrent.CompletionStage<InvocationSnapshot> getInvocation(String invocationRef);

    /**
     * Returns the cumulative number of raw response observations dropped by the optional observer queue.
     * This is a best-effort diagnostic counter and does not represent server-side data loss.
     *
     * @return dropped observation count
     */
    default long rawResponseDroppedCount() {
        return 0L;
    }

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

    /**
     * 关闭客户端拥有的本地观察和基础设施资源，不取消服务端 Task。
     *
     * <p>由 Builder 内部创建的 Transport 和工具执行器归客户端所有；外部注入资源默认应由调用方所有，
     * 除非构建时显式转移所有权。当前默认实现尚未区分资源来源，仍会关闭外部注入资源。
     */
    @Override
    void close();
}
