/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective logical agent when a create carries no {@code agentId}
 * (FEAT-011 L2 §0.4 / §4.4 P0 — "默认 Agent", usually an intent-recognition
 * agent). Configured by {@code gateway.default-agent-id}. A missing default is a
 * configuration error (not an S5 empty-route): the gateway cannot pick a target.
 *
 * @since 0.1.0
 */
@Component
public class DefaultAgentResolver {
    private final String defaultAgentId;

    /**
     * Construct.
     *
     * @param defaultAgentId configured default agent id (may be blank)
     */
    public DefaultAgentResolver(@Value("${gateway.default-agent-id:}") String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    /**
     * Resolve the configured default logical agent id.
     *
     * @return the configured default agent id
     * @throws GovernanceException 500 {@code DEFAULT_AGENT_UNCONFIGURED} if not configured
     */
    public String resolve() {
        if (defaultAgentId == null || defaultAgentId.isBlank()) {
            throw new GovernanceException(HttpStatus.INTERNAL_SERVER_ERROR, "DEFAULT_AGENT_UNCONFIGURED",
                    "No agentId in request and no default agent configured");
        }
        return defaultAgentId;
    }
}
