/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

/** Artifact.metadata.agentEvent 的已校验最小投影。 */
record AgentEvent(String type, AgentRef source, AgentRef target, String state) {
    record AgentRef(String agentId, String taskId) {
        boolean valid() {
            return agentId != null && !agentId.isBlank() && taskId != null && !taskId.isBlank();
        }
    }

    boolean valid() {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case "delegation" -> source != null && source.valid() && target != null && target.valid();
            case "output" -> source != null && source.valid();
            case "status" -> source != null && source.valid() && state != null && !state.isBlank();
            default -> false;
        };
    }
}
