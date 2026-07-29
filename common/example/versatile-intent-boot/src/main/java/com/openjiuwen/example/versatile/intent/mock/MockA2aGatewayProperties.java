/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the mock A2A gateway's optional A2A-native passthrough.
 *
 * <p>By default the gateway translates every inbound {@code /a2a/{agentId}}
 * into the target runtime's {@code /v1/query} (legacy versatile shape). When a
 * card is listed in {@link #passthroughCards}, the gateway instead forwards the
 * JSON-RPC body verbatim to the target's {@code /a2a/} endpoint, preserving
 * native A2A task states (e.g. {@code INPUT_REQUIRED}) and shadow-task resume.
 *
 * <p>{@link #routing} overrides entries in the static {@code ROUTING} table,
 * so the llm-intent demo can point business cards at Agent B ports without
 * disturbing the default routing used by {@code local-e2e-a2a-gateway.sh}.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.example.mock-a2a-gateway")
public class MockA2aGatewayProperties {

    /** agentCard → target base URL overrides, merged onto the static ROUTING. */
    private Map<String, String> routing = new LinkedHashMap<>();

    /** agentCards that bypass /v1/query translation and forward verbatim to /a2a/. */
    private Set<String> passthroughCards = new LinkedHashSet<>();

    public Map<String, String> getRouting() {
        return routing;
    }

    public void setRouting(Map<String, String> routing) {
        this.routing = routing == null ? new LinkedHashMap<>() : routing;
    }

    public Set<String> getPassthroughCards() {
        return passthroughCards;
    }

    public void setPassthroughCards(Set<String> passthroughCards) {
        this.passthroughCards = passthroughCards == null ? new LinkedHashSet<>() : passthroughCards;
    }
}
