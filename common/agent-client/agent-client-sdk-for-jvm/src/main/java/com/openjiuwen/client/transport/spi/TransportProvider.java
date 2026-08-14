/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationSnapshot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 传输层 SPI：把 SDK 的中立调用语义映射到具体 wire 协议（默认实现为 A2A JSON-RPC over HTTP + SSE）。
 *
 * <p>该接口是 SDK 内核与"协议/网络"之间的抽象缝，便于替换真实网关传输与进程内假网关（测试用）。
 * 传输层只处理"单次调用/单个 Task"的 wire 交互，不承担幂等去重、治理、多轮编排——那些在 internal 内核。
 *
 * @since 2026-07-27
 */
public interface TransportProvider extends AutoCloseable {
    /**
     * 创建并开始接收事件流。返回的 Publisher 会持续投递事件直至终态或需要续传。
     *
     * @param command 调用指令
     * @return 对应结果
     */
    Flow.Publisher<InvocationEvent> createAndStream(CreateCommand command);

    /**
     * 查询某个 Task 的权威状态快照（wire 方法 {@code GetTask}）。
     *
     * <p>凭据必须逐次传入：每一次出站 HTTP 都要带 {@code Authorization: Bearer}
     * （L2 Feat-Func-006 §3.5.0），查询也不例外。
     *
     * <p>v0730 网关北向已开放 {@code GetTask}；{@code CancelTask} 与 {@code SubscribeToTask}
     * 延至后续版本，故本 SPI <b>不提供</b> cancel / resubscribe（交付面即能力面）。
     *
     * @param taskRef 任务引用
     * @param credentialToken 本次请求凭据，可为 null
     * @return 状态快照
     */
    CompletionStage<InvocationSnapshot> getTask(String taskRef, String credentialToken);

    /**
     * 续跑既有 Task，返回该 Task 的<b>下一状态</b>快照。
     *
     * <p>wire method 由 {@code cmd.mode()} 决定并沿续跑继承首轮 invocation 的 mode（FEAT-006 §47）：
     * STREAMING 走 {@code SendStreamingMessage}（SSE，响应为帧流，折叠成单个快照结算返回 future），
     * BLOCKING/ASYNC 走 unary {@code SendMessage}（单次 JSON，返回时机由 mode 决定）。
     *
     * <p>返回快照必须是响应正文解析出的<b>完整</b>状态（含 outputText / pendingToolCall / errorCode），
     * 不能是占位的"working"快照——{@code continueInput} 依赖它驱动新 invocation 的事件流与结算。
     *
     * @param command 续跑指令
     * @return 该 Task 的下一状态快照
     */
    CompletionStage<InvocationSnapshot> resumeToolResult(ResumeCommand command);

    /**
     * 停止某次调用的本地观察资源。不得把该操作映射为服务端 CancelTask。
     *
     * @param invocationRef 调用句柄
     */
    default void closeObservation(String invocationRef) {
        // 兼容第三方 Transport；没有长期观察资源的实现无需处理。
    }

    @Override
    void close();

    /**
     * 创建调用的指令。
     *
     * @param invocationRef 调用句柄
     * @param invocationId 调用标识
     * @param idempotencyKey 幂等键
     * @param conversationId 会话标识
     * @param agentId Agent 标识
     * @param mode 调用模式
     * @param input 输入文本
     * @param clientTools 客户端工具规格
     * @param credentialToken 凭证令牌
     * @param relatedTaskRef 关联任务引用
     * @param attributes 业务附加属性（trace / correlation 等），随请求透传给网关；不含凭据
     */
    record CreateCommand(
            String invocationRef,
            String invocationId,
            String idempotencyKey,
            String conversationId,
            String agentId,
            InvocationMode mode,
            String input,
            List<ToolWireSpec> clientTools,
            String credentialToken,
            String relatedTaskRef,
            Map<String, String> attributes) {
        public CreateCommand {
            attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
        }

        /**
         * 不携带附加属性的创建指令。
         *
         * @param invocationRef 调用句柄
         * @param invocationId 调用标识
         * @param idempotencyKey 幂等键
         * @param conversationId 会话标识
         * @param agentId Agent 标识
         * @param mode 调用模式
         * @param input 输入文本
         * @param clientTools 客户端工具规格
         * @param credentialToken 凭证令牌
         * @param relatedTaskRef 关联任务引用
         */
        public CreateCommand(String invocationRef, String invocationId, String idempotencyKey,
                             String conversationId, String agentId, InvocationMode mode, String input,
                             List<ToolWireSpec> clientTools, String credentialToken, String relatedTaskRef) {
            this(invocationRef, invocationId, idempotencyKey, conversationId, agentId, mode, input,
                    clientTools, credentialToken, relatedTaskRef, Map.of());
        }
    }

    /**
     * 续跑响应帧的投递归属。wire 形态两者相同，但<b>事件归谁</b>不同，传输层必须区分（FRZ-1）。
     *
     * <p>判据不是"工具结果还是用户输入"，而是"发起本次续跑的 invocation 由谁驱动"：
     * 由 {@code invoke} 创建的 invocation 有自己的事件流通道；由 {@code continueInput} 创建的
     * 新 invocation 没有通道，只能靠续跑响应快照驱动。
     */
    enum ResumeDelivery {
        /**
         * 汇入既有调用的事件流：适用于 {@code invoke} 创建的 invocation 内部的工具结果续跑，
         * 业务侧看到的是一条连续流。
         */
        EXISTING_STREAM,
        /**
         * 仅作为返回快照：适用于 {@code continueInput} 新建的 invocation（含其内部的后续工具续跑）。
         * 响应帧<b>不得</b>汇入任何既有事件流，否则会投递到错误的 invocation 上。
         */
        SNAPSHOT_ONLY
    }

    /**
     * 续跑指令。{@code observationText} 是已渲染好的服务端可消费文本（工具结果）或用户补充输入文本。
     *
     * <p>{@code mode} <b>强制继承</b>首轮 invocation 的 mode（FEAT-006 §47）：由 {@code DefaultAgentClient}
     * 在构造本指令时从首轮 {@code InvocationState.mode} 填入，业务不得覆盖。wire method 据此选择
     * {@code SendStreamingMessage}（STREAMING）或 {@code SendMessage}（BLOCKING/ASYNC）。
     *
     * @param invocationRef 调用句柄；{@code USER_INPUT} 时是<b>新</b>建的 invocationRef
     * @param taskRef 任务引用（关联既有 Task 的唯一依据）
     * @param messageId 消息标识，每次续跑新生成
     * @param toolCallId 工具调用标识；仅本地去重用，不上 wire（FRZ-2）
     * @param observationText 回传文本
     * @param mode 调用模式（继承自首轮 invocation）
     * @param clientTools 客户端工具规格
     * @param credentialToken 凭证令牌
     * @param conversationId 会话标识
     * @param delivery 响应帧的投递归属
     */
    record ResumeCommand(
            String invocationRef,
            String taskRef,
            String messageId,
            String toolCallId,
            String observationText,
            InvocationMode mode,
            List<ToolWireSpec> clientTools,
            String credentialToken,
            String conversationId,
            ResumeDelivery delivery) {
        /**
         * 默认汇入既有调用的事件流。
         *
         * @param invocationRef 调用句柄
         * @param taskRef 任务引用
         * @param messageId 消息标识
         * @param toolCallId 工具调用标识
         * @param observationText 回传文本
         * @param mode 调用模式
         * @param clientTools 客户端工具规格
         * @param credentialToken 凭证令牌
         * @param conversationId 会话标识
         */
        public ResumeCommand(String invocationRef, String taskRef, String messageId, String toolCallId,
                             String observationText, InvocationMode mode, List<ToolWireSpec> clientTools,
                             String credentialToken, String conversationId) {
            this(invocationRef, taskRef, messageId, toolCallId, observationText, mode,
                    clientTools, credentialToken, conversationId, ResumeDelivery.EXISTING_STREAM);
        }
    }
}
