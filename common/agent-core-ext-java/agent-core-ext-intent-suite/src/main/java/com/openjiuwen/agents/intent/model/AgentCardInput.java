/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.Objects;

/**
 * Runtime Agent Card and its stable remote registry identifier.
 *
 * @param agentCard protocol Agent Card
 * @param remoteAgentId stable remote registry identifier
 *
 * @since 0.1.0
 */
public record AgentCardInput(AgentCard agentCard, String remoteAgentId) {
    /**
     * Validates required input fields.
     */
    public AgentCardInput {
        agentCard = Objects.requireNonNull(agentCard, "agentCard");
        remoteAgentId = requireText(remoteAgentId, "remoteAgentId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
