/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.List;
import java.util.Map;

/**
 * Deterministic caller/callee handlers for the Runtime-to-Runtime Agent Bus E2E.
 *
 * @since 2026-08-05
 */
final class RuntimeBusDemoAgentHandler implements AgentHandler {
    static final String TARGET_AGENT_ID = "target-agent";
    static final String TOOL_CALL_ID = "call-target-runtime";
    static final String INPUT_REQUIRED_TRIGGER = "request target approval";
    static final int TARGET_STREAM_CHUNKS = 6;
    static final long TARGET_STREAM_DELAY_MILLIS = 300L;

    /**
     * S1 专属 conversationId：仅此会话走 client_tool 中断序列，用于完整复现 gateway
     * {@code BusForwarder.drainStreamToClient} 在 INPUT_REQUIRED 后空等 TERMINAL 的 bug（§6.3-A）。
     * <p>用 conversationId 而非 input 隔离，是因为 S12（scenarioExpiredExposure）故意用与 S1 相同的
     * input（"please read the page then submit the order"）测过期窗口，input 无法区分；conv-stream-1
     * 是 S1 专属，其余场景各用不同 conversationId，互不影响。
     */
    static final String CLIENT_TOOL_SEQUENCE_CONVERSATION = "conv-stream-1";

    /**
     * metadata key：runtime 在 INPUT_REQUIRED 续传时回放上轮 {@code _interrupt}（见 A2AAgentExecutor.execute）。
     * handler 借此区分续传轮次，推进 readPage → submitOrder → COMPLETED 状态机。
     */
    private static final String INTERRUPT_META = "_interrupt";

    private final boolean caller;
    private final int streamChunks;
    private final long streamDelayMillis;

    private RuntimeBusDemoAgentHandler(boolean caller, int streamChunks, long streamDelayMillis) {
        this.caller = caller;
        this.streamChunks = streamChunks;
        this.streamDelayMillis = streamDelayMillis;
    }

    static RuntimeBusDemoAgentHandler caller() {
        return new RuntimeBusDemoAgentHandler(true, 1, 0L);
    }

    static RuntimeBusDemoAgentHandler callee() {
        return callee(TARGET_STREAM_CHUNKS, TARGET_STREAM_DELAY_MILLIS);
    }

    static RuntimeBusDemoAgentHandler callee(int streamChunks, long streamDelayMillis) {
        return new RuntimeBusDemoAgentHandler(false, streamChunks, streamDelayMillis);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (!caller) {
            if (CLIENT_TOOL_SEQUENCE_CONVERSATION.equals(request.getConversationId())) {
                return clientToolSequenceResponse(request);
            }
            if (INPUT_REQUIRED_TRIGGER.equals(request.lastUserQuery())) {
                return inputRequired(request);
            }
            return response(request, "target runtime received: " + request.lastUserQuery());
        }
        Object remoteResult = remoteResult(request);
        if (remoteResult != null) {
            return response(request, "source runtime received remote result: " + remoteResult);
        }
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", interrupt(request)),
                request.getConversationId());
    }

    /**
     * Sync (query) version of the conv-stream-1 client_tool sequence — the resume (SendMessage)
     * goes through query, not streamQuery. After readPage → send submitOrder interrupt; after
     * submitOrder → COMPLETED.
     */
    private QueryResponse clientToolSequenceResponse(ServeRequest request) {
        String previousToolName = previousInterruptToolName(request);
        if ("readPage".equals(previousToolName)) {
            return new QueryResponse(Map.of("role", "assistant", "_interrupt",
                    clientToolInterrupt("call-submitorder-1", "submitOrder", "please submit the order")),
                    request.getConversationId());
        }
        return response(request, "order submitted successfully");
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (caller && remoteResult(request) == null) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt(request)));
            return;
        }
        if (!caller) {
            if (CLIENT_TOOL_SEQUENCE_CONVERSATION.equals(request.getConversationId())) {
                streamClientToolSequence(request, observer);
                return;
            }
            streamTargetResponse(request, observer);
            return;
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
        observer.onComplete();
    }

    private void streamTargetResponse(ServeRequest request, QueryStreamObserver observer) {
        for (int index = 1; index <= streamChunks; index++) {
            if (observer.isCancelled()) {
                return;
            }
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    response(request, "target stream chunk " + index + "/" + streamChunks + ": "
                            + request.lastUserQuery()).getResult()));
            if (index < streamChunks && !pause(observer)) {
                return;
            }
        }
        observer.onComplete();
    }

    /**
     * S1 专用：按续传轮次驱动 readPage → submitOrder → COMPLETED 的 client_tool 中断序列，完整复现
     * gateway {@code drainStreamToClient} 在 INPUT_REQUIRED 后空等 TERMINAL 的 bug。
     *
     * <p>轮次判定靠 {@code metadata._interrupt}（runtime 在 INPUT_REQUIRED 续传时回放上轮 interrupt）：
     * <ul>
     *   <li>轮0（无上轮 interrupt）：发 {@code readPage}（client_tool，SDK 自动执行）→ INPUT_REQUIRED</li>
     *   <li>轮1（上轮 readPage）：发 {@code submitOrder}（ACTION，SDK 审批后执行）→ INPUT_REQUIRED</li>
     *   <li>轮2（上轮 submitOrder）：发终态文本 → COMPLETED</li>
     * </ul>
     * gateway bug 存在时，前两轮 INPUT_REQUIRED 后 gateway 空等 TERMINAL 占用 servlet 线程，
     * 续传响应无法及时汇回原流 → S1 超时（即 bug 复现点）。bug 修复后此序列可正常收敛。
     *
     * @param request the serve request
     * @param observer the stream observer
     */
    private void streamClientToolSequence(ServeRequest request, QueryStreamObserver observer) {
        String previousToolName = previousInterruptToolName(request);
        if (previousToolName == null) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    clientToolInterrupt("call-readpage-1", "readPage", "please read the page")));
            return;
        }
        if ("readPage".equals(previousToolName)) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    clientToolInterrupt("call-submitorder-1", "submitOrder", "please submit the order")));
            return;
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                response(request, "order submitted successfully").getResult()));
        observer.onComplete();
    }

    private static String previousInterruptToolName(ServeRequest request) {
        if (request.getMetadata() == null) {
            return null;
        }
        Object value = request.getMetadata().get(INTERRUPT_META);
        if (!(value instanceof Map<?, ?> interrupt)) {
            return null;
        }
        return interrupt.get("toolName") instanceof String toolName ? toolName : null;
    }

    /**
     * 构造 client_tool 中断的 {@code _interrupt} 内层 map。结构对齐 agent-client-sdk
     * {@code A2aJsonCodec.parseInterrupt}：{@code toolCallId}/{@code toolName}/{@code message}
     * 顶层字段，{@code context._interrupt_kind="client_tool"} 标识由 SDK 自动执行。
     *
     * @param toolCallId the tool call id
     * @param toolName the tool name (readPage / submitOrder)
     * @param message the prompt message
     * @return the interrupt map
     */
    private static Map<String, Object> clientToolInterrupt(String toolCallId, String toolName, String message) {
        Map<String, Object> arguments = switch (toolName) {
            case "readPage" -> Map.of("pageId", "page-1");
            case "submitOrder" -> Map.of("orderId", "order-1");
            default -> Map.of();
        };
        return Map.of(
                "toolCallId", toolCallId,
                "toolName", toolName,
                "message", message,
                "context", Map.of("_interrupt_kind", "client_tool", "arguments", arguments));
    }

    private boolean pause(QueryStreamObserver observer) {
        try {
            Thread.sleep(streamDelayMillis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            observer.onError(interrupted);
            return false;
        }
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content + echoSuffix(request),
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId()),
                request.getConversationId());
    }

    /**
     * 把请求携带的业务属性回显进输出文本后缀，供 S11（traceId）/ S13（agentId）断言验证属性确实上 wire
     * 并到达 runtime。
     *
     * <p>读取路径（对齐 SDK {@code A2aJsonCodec.fillMetadata} 与 runtime {@code A2AProtocolAdapter}）：
     * <ul>
     *   <li>{@code metadata.attributes.traceId} — SDK 经 {@code .attribute("traceId", ...)} 传入</li>
     *   <li>{@code metadata.agentId} — SDK 经 {@code .agentId(...)} 传入，gateway 路由后透传给 runtime</li>
     * </ul>
     * 不存在则对应后缀省略，不影响其他场景的输出。
     *
     * @param request the serve request
     * @return echo suffix string, empty when no traceable attributes present
     */
    private static String echoSuffix(ServeRequest request) {
        StringBuilder suffix = new StringBuilder();
        if (request.getMetadata() != null) {
            Object attrs = request.getMetadata().get("attributes");
            if (attrs instanceof Map<?, ?> attrMap && attrMap.get("traceId") instanceof String traceId
                    && !traceId.isBlank()) {
                suffix.append("[trace=").append(traceId).append("]");
            }
            if (request.getMetadata().get("agentId") instanceof String agentId && !agentId.isBlank()) {
                suffix.append("[agent=").append(agentId).append("]");
            }
        }
        return suffix.toString();
    }

    private static QueryResponse inputRequired(ServeRequest request) {
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", Map.of(
                "message", "Approve target runtime operation?",
                "context", Map.of("_interrupt_kind", "ask_user"))), request.getConversationId());
    }

    private static Map<String, Object> interrupt(ServeRequest request) {
        return Map.of("batchId", "runtime-bus-runtime-e2e", "items", List.of(Map.of(
                "index", 0,
                "toolCallId", TOOL_CALL_ID,
                "toolName", "delegate-target-runtime",
                "message", request.lastUserQuery(),
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", TARGET_AGENT_ID))));
    }

    private static Object remoteResult(ServeRequest request) {
        if (request.getMetadata() == null) {
            return null;
        }
        Object value = request.getMetadata().get("runtime.remoteToolResults");
        if (!(value instanceof Map<?, ?> results)) {
            return null;
        }
        return results.get(TOOL_CALL_ID);
    }
}
