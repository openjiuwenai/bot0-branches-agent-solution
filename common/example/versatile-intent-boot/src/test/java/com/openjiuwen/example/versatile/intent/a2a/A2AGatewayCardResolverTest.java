/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link A2AGatewayCardResolver}.
 *
 * @since 0.1.0
 */
class A2AGatewayCardResolverTest {
    private A2AGatewayProperties props;
    private A2AGatewayCardResolver resolver;

    @BeforeEach
    void setUp() {
        props = new A2AGatewayProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://gateway.example.com");
        props.setJsonRpcPath("/a2a/{agentCard}");
        resolver = new A2AGatewayCardResolver(props);
    }

    @Test
    void resolveCardUrlReturnsEmptyInGatewayMode() {
        assertThat(resolver.resolveCardUrl("agent_card_L2_hotel")).isEmpty();
    }

    @Test
    void resolveJsonRpcUrlSubstitutesAgentCardPlaceholder() {
        assertThat(resolver.resolveJsonRpcUrl("agent_card_L2_hotel"))
                .isEqualTo("https://gateway.example.com/a2a/agent_card_L2_hotel");
    }

    @Test
    void resolveJsonRpcUrlUsesDefaultGatewayConventionWhenNotOverridden() {
        A2AGatewayProperties defaults = new A2AGatewayProperties();
        defaults.setBaseUrl("https://gateway.example.com");
        A2AGatewayCardResolver r = new A2AGatewayCardResolver(defaults);

        assertThat(r.resolveJsonRpcUrl("agent_L1"))
                .isEqualTo("https://gateway.example.com/a2a/agent_L1");
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
        assertThat(r.resolveJsonRpcUrl("agent_card_L2_hotel")).isEmpty();
    }
}
