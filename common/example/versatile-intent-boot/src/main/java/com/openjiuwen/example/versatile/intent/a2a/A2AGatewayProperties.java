/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the A2A Gateway routing mode.
 *
 * <p>Bound to {@code openjiuwen.service.a2a-gateway.*}. When
 * {@link #isEnabled} is {@code true}, the deployment module injects
 * {@link A2AGatewayRemoteAgentCaller} / {@link A2AGatewayCardResolver} as the
 * active {@code RemoteAgentCaller} / {@code RemoteAgentCardResolver} beans,
 * overriding the runtime core module's {@code Default*} implementations.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.a2a-gateway")
public class A2AGatewayProperties {
    private boolean enabled = false;
    private String baseUrl;
    private String agentCardPath = "/{agentCard}/.well-known/agent-card.json";
    private String jsonRpcPath = "/{agentCard}/a2a";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAgentCardPath() {
        return agentCardPath;
    }

    public void setAgentCardPath(String agentCardPath) {
        this.agentCardPath = agentCardPath;
    }

    public String getJsonRpcPath() {
        return jsonRpcPath;
    }

    public void setJsonRpcPath(String jsonRpcPath) {
        this.jsonRpcPath = jsonRpcPath;
    }
}
