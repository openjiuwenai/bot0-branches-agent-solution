/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class A2AGatewayCardResolverTest {
    private A2AGatewayProperties props;
    private A2AGatewayCardResolver resolver;

    @BeforeEach
    void setUp() {
        props = new A2AGatewayProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://gateway.example.com");
        props.setAgentCardPath("/{agentCard}/.well-known/agent-card.json");
        props.setJsonRpcPath("/{agentCard}/a2a");
        resolver = new A2AGatewayCardResolver(props);
    }

    @Test
    void resolveCardUrlSubstitutesAgentCardPlaceholder() {
        assertThat(resolver.resolveCardUrl("agent_card_L2_hotel"))
                .isEqualTo("https://gateway.example.com/agent_card_L2_hotel/.well-known/agent-card.json");
    }

    @Test
    void resolveJsonRpcUrlSubstitutesAgentCardPlaceholder() {
        assertThat(resolver.resolveJsonRpcUrl("agent_card_L2_hotel"))
                .isEqualTo("https://gateway.example.com/agent_card_L2_hotel/a2a");
    }

    @Test
    void supportedReturnsTrueForAnyNonBlankAgentId() {
        assertThat(resolver.supported("agent_card_L2_hotel")).isTrue();
        assertThat(resolver.supported("")).isFalse();
        assertThat(resolver.supported(null)).isFalse();
    }

    @Test
    void returnsEmptyWhenBaseUrlNotConfigured() {
        A2AGatewayProperties empty = new A2AGatewayProperties();
        A2AGatewayCardResolver r = new A2AGatewayCardResolver(empty);
        assertThat(r.resolveCardUrl("agent_card_L2_hotel")).isEmpty();
        assertThat(r.resolveJsonRpcUrl("agent_card_L2_hotel")).isEmpty();
    }
}
