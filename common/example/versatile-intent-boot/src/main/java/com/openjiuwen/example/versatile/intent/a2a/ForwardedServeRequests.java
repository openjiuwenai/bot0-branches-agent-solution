/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds forwarded {@link ServeRequest} instances for cross-layer A2A calls.
 *
 * <p>Shared by {@link A2AGatewayRemoteAgentCaller} and
 * {@link LocalHttpRemoteAgentCaller} to append the upstream
 * {@code response_content} as an {@code assistant} message to the messages
 * array while preserving {@code lastUserQuery()} (per L2 §4.9.3).
 *
 * @since 0.1.0
 */
final class ForwardedServeRequests {
    private ForwardedServeRequests() {
    }

    /**
     * Constructs a new {@link ServeRequest} carrying the original conversation
     * context with the upstream {@code responseContent} appended as an
     * assistant message.
     *
     * <p><b>Limitation — messages.last replacement not implemented.</b>
     * L2 §4.9.3 重新分类场景要求 messages 最后一条 user 消息 content 为
     * {@code responseContent}（重分类上下文），一层 Adapter 通过
     * {@code lastUserQuery()} 取重分类上下文作为 query。当前实现仅追加
     * assistant 消息，<b>未做 messages.last 替换</b>。Caller 无法区分
     * "正常跨层转发"（一层→二层，{@code lastUserQuery()} 不变）与"重新分类"
     * （业务工作流→一层，{@code lastUserQuery()} 替换为 {@code responseContent}）
     * ——两者走同一个 {@code buildForwardCall}
     * （{@code A2AEnabledServeOrchestrator.java:657}），构造的
     * {@code RemoteAgentCall} 字段完全相同。完整修复需要 orchestrator 在
     * 重新分类时传递场景信号（如设置 {@code call.message()=responseContent}）
     * 或由 orchestrator 直接构造替换后的 ServeRequest，并同步更新 L2 §4.9.3
     * 契约。此 gap 由 follow-up issue 跟踪（跨仓库：agent-runtime-java +
     * spring-ai-ascend）。
     *
     * @param original        the original serve request
     * @param responseContent optional upstream response content; when blank no
     *                        assistant message is appended
     * @return a new serve request with messages appended
     */
    static ServeRequest build(ServeRequest original, String responseContent) {
        ServeRequest forwarded = new ServeRequest();
        forwarded.setConversationId(original.getConversationId());
        forwarded.setUserId(original.getUserId());
        forwarded.setSpaceId(original.getSpaceId());
        forwarded.setTenantId(original.getTenantId());
        forwarded.setStream(original.isStream());
        forwarded.setMetadata(original.getMetadata());
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        if (responseContent != null && !responseContent.isBlank()) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", responseContent);
            messages.add(assistant);
        }
        forwarded.setMessages(messages);
        return forwarded;
    }
}
