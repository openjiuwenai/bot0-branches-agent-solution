/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.tool.spi;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本地工具执行的上下文（FEAT-007）。承载与本次执行相关的租户/链路/期限等信息，
 * 供工具实现与治理（PolicyGuard/ApprovalProvider）读取。
 *
 * <p>{@code visibleToolNames} 是本次 invocation 上报给服务端的 ToolView 投影工具名集合，
 * 治理管道据此做"可见性校验"：服务端幻觉调用未暴露的工具 → {@code tool_not_declared} 拒绝（FEAT-007 §3.2 步骤 3）。
 *
 * @param conversationId 会话标识
 * @param invocationRef 调用句柄
 * @param traceId 追踪标识
 * @param deadline 截止时间
 * @param attributes 属性
 * @param visibleToolNames 可见工具名
 * @since 2026-07-27
 */
public record ToolExecutionContext(
        String conversationId,
        String invocationRef,
        String traceId,
        Duration deadline,
        Map<String, String> attributes,
        Set<String> visibleToolNames) {
    public ToolExecutionContext {
        attributes = (attributes == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        visibleToolNames = (visibleToolNames == null)
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(visibleToolNames));
    }

    /**
     * 便捷构造（无可见工具集合，仅用于不需要可见性校验的场景）。
     *
     * @param conversationId 会话标识
     * @param invocationRef 调用句柄
     * @param traceId 跟踪标识
     * @param deadline 截止时间
     * @param attributes 附加属性
     */
    public ToolExecutionContext(String conversationId, String invocationRef, String traceId,
                                Duration deadline, Map<String, String> attributes) {
        this(conversationId, invocationRef, traceId, deadline, attributes, Set.of());
    }
}
