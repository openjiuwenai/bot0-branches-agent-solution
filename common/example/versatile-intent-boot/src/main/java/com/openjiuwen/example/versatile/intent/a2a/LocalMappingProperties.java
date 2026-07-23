/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for local agentCard → URL mapping (L2 §5.5.3 方案 B).
 *
 * <p>Each entry maps an {@code agentCard} name to a base URL (e.g.
 * {@code http://localhost:8082}). The {@link LocalMappingCardRegistrar}
 * registers an ephemeral {@link org.a2aproject.sdk.spec.AgentCard} for each
 * entry into {@code A2ARemoteAgentCardRegistry}, pointing the JSON-RPC URL at
 * {@code <baseUrl>/a2a}. This lets {@code DefaultRemoteAgentCaller} route
 * cross-layer calls to localhost without the A2A Gateway.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.card-resolver")
public class LocalMappingProperties {
    private Map<String, String> localMapping = new LinkedHashMap<>();

    public Map<String, String> getLocalMapping() {
        return localMapping;
    }

    public void setLocalMapping(Map<String, String> localMapping) {
        this.localMapping = localMapping != null ? new LinkedHashMap<>(localMapping) : new LinkedHashMap<>();
    }
}
