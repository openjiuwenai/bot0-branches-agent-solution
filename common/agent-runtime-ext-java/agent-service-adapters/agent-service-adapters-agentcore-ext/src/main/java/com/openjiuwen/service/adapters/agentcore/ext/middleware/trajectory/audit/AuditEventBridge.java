/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import java.util.Map;

/**
 * 静态审计事件桥：批一 rail（SessionFilteredAgentRail）与安全决策 aspect 等无法经
 * Spring 注入拿到收集器的挂点，经本桥上报事件。未注册收集器时全部为 no-op——
 * 未启用 trajectory.link 或 Redis 缺失（V-8）时零行为变化。
 *
 * @since 2026-08-26
 */
public final class AuditEventBridge {
    private static volatile AuditEventCollector collector;

    private AuditEventBridge() {
    }

    /**
     * Registers the collector (auto-configuration, when the assembly is up).
     *
     * @param auditEventCollector collector instance
     */
    public static void register(AuditEventCollector auditEventCollector) {
        collector = auditEventCollector;
    }

    /**
     * Clears the registration (shutdown / tests).
     */
    public static void clear() {
        collector = null;
    }

    /**
     * Forwards a tool call event.
     *
     * @param conversationId conversation id
     * @param toolName       tool name
     * @param status         finish / error
     * @param elapsedMs      elapsed milliseconds (0 when unavailable)
     */
    public static void recordToolCall(String conversationId, String toolName, String status, long elapsedMs) {
        AuditEventCollector current = collector;
        if (current != null) {
            current.recordToolCall(conversationId, toolName, status, elapsedMs);
        }
    }

    /**
     * Forwards a user query / agent answer summary pair.
     *
     * @param conversationId conversation id
     * @param userQuery      user query
     * @param agentAnswer    agent answer
     */
    public static void recordExchange(String conversationId, String userQuery, String agentAnswer) {
        AuditEventCollector current = collector;
        if (current != null) {
            current.recordExchange(conversationId, userQuery, agentAnswer);
        }
    }

    /**
     * Forwards a decision evidence event.
     *
     * @param tenantId       tenant id (may be null)
     * @param conversationId conversation id (may be null when unavailable)
     * @param type           decision type
     * @param fields         decision fields
     */
    public static void recordDecision(String tenantId, String conversationId, String type,
                                      Map<String, Object> fields) {
        AuditEventCollector current = collector;
        if (current != null && conversationId != null) {
            current.recordDecision(tenantId, conversationId, type, fields);
        }
    }
}
