/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016.agent;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Map;

/**
 * FEAT-016 AgentDemo 的目标 Agent — 一个无大模型依赖的确定性 stub。
 *
 * <p>仿照 {@code agent-bus-consumer-callee-demo} 的 {@code CalleeAgentHandler}：
 * 不调用任何真实 LLM，直接把用户输入原样回显并加上 mock 标记，使 A2A 往返
 * 可被客户端断言。{@code agent-service-app} 检测到本 Bean 后自动在 {@code /a2a}
 * 暴露 A2A JSON-RPC 端点（SendStreamingMessage / SendMessage / GetTask）。
 *
 * <p>同时实现非流式 {@link #query} 与流式 {@link #streamQuery}，覆盖 A2A 的两种
 * 调用模式，让客户端既能验证 unary 响应，也能验证 SSE 流式帧。
 *
 * @since 0.1.0 (2026)
 */
public final class Fe016StubAgentHandler implements AgentHandler {

    public static final String MOCK_REPLY_PREFIX = "[mock-llm] ";

    @Override
    public QueryResponse query(ServeRequest request) {
        return response(request);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (observer.isCancelled()) {
            return;
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, response(request).getResult()));
        observer.onComplete();
    }

    private static QueryResponse response(ServeRequest request) {
        String userQuery = request.lastUserQuery();
        String content = MOCK_REPLY_PREFIX + "echo: " + (userQuery == null ? "" : userQuery);
        String tenantId = request.getTenantId() == null ? "" : request.getTenantId();
        String conversationId = request.getConversationId();
        return new QueryResponse(
                Map.of("role", "assistant", "content", content, "tenantId", tenantId),
                conversationId);
    }
}
