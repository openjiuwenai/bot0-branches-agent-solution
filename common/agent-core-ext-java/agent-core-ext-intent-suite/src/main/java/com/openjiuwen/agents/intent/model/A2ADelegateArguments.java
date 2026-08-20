/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Runtime target bound to all compatible skills of one Agent Card.
 *
 * @param remoteAgentId stable remote registry identifier
 *
 * @since 0.1.0
 */
public record A2ADelegateArguments(String remoteAgentId) implements IntentResultArguments {
    /**
     * Validates the remote target.
     */
    public A2ADelegateArguments {
        if (remoteAgentId == null || remoteAgentId.isBlank()) {
            throw new IllegalArgumentException("remoteAgentId must not be blank");
        }
        remoteAgentId = remoteAgentId.trim();
    }
}
