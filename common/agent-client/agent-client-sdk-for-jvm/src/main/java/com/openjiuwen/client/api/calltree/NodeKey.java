/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

import java.util.Objects;

/** Agent 调用实例的结构化标识。 */
public record NodeKey(String agentId, String taskId) {
    public NodeKey {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }
}
