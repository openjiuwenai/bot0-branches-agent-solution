/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable conversation message exposed to intent SPI implementations.
 *
 * @param role message role
 * @param contentSnapshot immutable content snapshot
 * @param name optional sender name
 * @param metadata immutable metadata
 * @param toolCalls assistant ToolCalls
 * @param toolCallId ToolMessage correlation identifier
 */
public record IntentMessageSnapshot(String role, Object contentSnapshot, String name, Map<String, Object> metadata,
        List<IntentToolCallSnapshot> toolCalls, String toolCallId) {
    /**
     * Creates immutable message collections.
     */
    public IntentMessageSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
