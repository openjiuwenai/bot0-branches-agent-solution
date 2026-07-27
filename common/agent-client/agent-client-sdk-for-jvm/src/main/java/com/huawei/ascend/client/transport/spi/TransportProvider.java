/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.spi;

import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationSnapshot;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 传输层 SPI：把 SDK 的中立调用语义映射到具体 wire 协议（默认实现为 A2A JSON-RPC over HTTP + SSE）。
 *
 * <p>该接口是 SDK 内核与"协议/网络"之间的抽象缝，便于替换真实网关传输与进程内假网关（测试用）。
 * 传输层只处理"单次调用/单个 Task"的 wire 交互，不承担幂等去重、治理、多轮编排——那些在 internal 内核。
 */
public interface TransportProvider extends AutoCloseable {

    /** 创建并开始接收事件流。返回的 Publisher 会持续投递事件直至终态或需要续传。 */
    Flow.Publisher<InvocationEvent> createAndStream(CreateCommand command);

    /** 依据服务端 taskRef 拉取权威快照（{@code tasks/get}）。 */
    CompletionStage<InvocationSnapshot> getTask(String taskRef);

    /** 请求取消（{@code tasks/cancel}）。网关不支持时实现方可返回当前快照以降级。 */
    CompletionStage<InvocationSnapshot> cancel(String taskRef, String reason);

    /** 提交一次本地工具结果续传（对既有 taskRef 的同步 {@code SendMessage}，携带 observation 文本）。 */
    CompletionStage<InvocationSnapshot> resumeToolResult(ResumeCommand command);

    @Override
    void close();

    /** 创建调用的指令。 */
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
            String relatedTaskRef) {
    }

    /** 续传工具结果的指令。{@code observationText} 是已渲染好的服务端可消费文本。 */
    record ResumeCommand(
            String invocationRef,
            String taskRef,
            String messageId,
            String toolCallId,
            String observationText,
            InvocationMode mode,
            List<ToolWireSpec> clientTools,
            String credentialToken,
            String conversationId) {
    }
}
