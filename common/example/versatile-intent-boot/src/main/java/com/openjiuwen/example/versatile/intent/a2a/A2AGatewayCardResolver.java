/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;

/**
 * A2A Gateway card URL resolver: substitutes the {@code {agentCard}} placeholder
 * in {@link A2AGatewayProperties#getAgentCardPath()} / {@link A2AGatewayProperties#getJsonRpcPath()}
 * with the runtime {@code agentId}.
 *
 * @since 0.1.0
 */
public class A2AGatewayCardResolver implements RemoteAgentCardResolver {
    private static final String PLACEHOLDER = "{agentCard}";

    private final A2AGatewayProperties properties;

    /**
     * Constructs the resolver.
     *
     * @param properties the gateway properties
     */
    public A2AGatewayCardResolver(A2AGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String resolveCardUrl(String agentId) {
        return substitute(agentId, properties.getBaseUrl(), properties.getAgentCardPath());
    }

    @Override
    public String resolveJsonRpcUrl(String agentId) {
        return substitute(agentId, properties.getBaseUrl(), properties.getJsonRpcPath());
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && !agentId.isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    private static String substitute(String agentId, String baseUrl, String pathTemplate) {
        if (agentId == null || agentId.isBlank() || baseUrl == null || baseUrl.isBlank()
                || pathTemplate == null) {
            return "";
        }
        String base = baseUrl.replaceAll("/$", "");
        String path = pathTemplate.replace(PLACEHOLDER, agentId);
        return base + path;
    }
}
