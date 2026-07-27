/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * {@link DirectChainProperties} 默认值与 setter 验证。
 *
 * @since 0.1.0
 */
class DirectChainPropertiesTest {
    @Test
    void defaultsAreSane() {
        DirectChainProperties p = new DirectChainProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getA2aForwardAgentCards()).isEmpty(); // 默认空 => 默认全直链
        assertThat(p.getTimeout()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void settersWork() {
        DirectChainProperties p = new DirectChainProperties();
        p.setEnabled(true);
        p.setA2aForwardAgentCards(java.util.Set.of("agent_card_L2_default")); // 仅例外走 a2a
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getA2aForwardAgentCards()).contains("agent_card_L2_default");
    }
}
