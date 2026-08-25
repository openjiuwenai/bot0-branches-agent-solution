/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.schema;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.Map;

/**
 * Minimal DSL node shell validation shared by built-in and custom nodes (L2 §4.7.2).
 * Does not validate type-specific configs.
 *
 * @since 2026-08-17
 */
public final class DslNodeShellValidator {
    private DslNodeShellValidator() {}

    /**
     * validateShell.
     *
     * @param node node
     */
    public static void validateShell(AssembledNode node) {
        if (node == null) {
            throw new NodeExecutionException("n/a", "unknown", NodeCauseCode.NODE_CONFIG_INVALID, "node is null");
        }
        if (node.id() == null || node.id().isBlank()) {
            throw new NodeExecutionException("n/a", node.irType(), NodeCauseCode.NODE_CONFIG_INVALID, "id required");
        }
        if (node.irType() == null || node.irType().isBlank()) {
            throw new NodeExecutionException(node.id(), "unknown", NodeCauseCode.NODE_CONFIG_INVALID, "type required");
        }
        Map<String, Object> configs = node.configs();
        if (configs == null) {
            throw new NodeExecutionException(
                    node.id(), node.irType(), NodeCauseCode.NODE_CONFIG_INVALID, "configs required");
        }
    }

    /**
     * Validate a raw DSL/IR map before AssembledNode construction.
     *
     * @param raw raw
     */
    public static void validateRaw(Map<String, Object> raw) {
        if (raw == null) {
            throw new NodeExecutionException("n/a", "unknown", NodeCauseCode.NODE_CONFIG_INVALID, "node map is null");
        }
        Object id = raw.get("id");
        Object type = raw.containsKey("type") ? raw.get("type") : raw.get("irType");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new NodeExecutionException(
                    "n/a", String.valueOf(type), NodeCauseCode.NODE_CONFIG_INVALID, "id required");
        }
        if (type == null || String.valueOf(type).isBlank()) {
            throw new NodeExecutionException(
                    String.valueOf(id), "unknown", NodeCauseCode.NODE_CONFIG_INVALID, "type required");
        }
        if (!raw.containsKey("configs") || !(raw.get("configs") instanceof Map<?, ?>)) {
            throw new NodeExecutionException(
                    String.valueOf(id),
                    String.valueOf(type),
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "configs object required");
        }
    }
}
