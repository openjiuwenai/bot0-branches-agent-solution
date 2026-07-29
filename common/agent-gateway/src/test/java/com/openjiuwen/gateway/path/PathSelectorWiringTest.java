/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.gateway.bus.control.FakeBrokerForwardingConsumerPort;
import com.openjiuwen.gateway.bus.control.FakeBrokerForwardingProducerPort;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration test: {@link PathSelector} is wired by Spring from
 * {@code gateway.path-mode} config (FEAT-012 §0.2 — deployment-level,
 * not client-visible).
 *
 * <p>Default config (no property) → DIRECT is covered by the full-context smoke
 * ({@code A2aRouteSmokeTest}); this class verifies the {@code bus} override.
 *
 * @since 2026-07-24
 */
@SpringBootTest(properties = "gateway.path-mode=bus")
@Import({
        FakeForwardingOutboxPort.class,
        FakeBrokerForwardingConsumerPort.class,
        FakeBrokerForwardingProducerPort.class
})
class PathSelectorWiringTest {
    @Autowired
    private PathSelector pathSelector;

    @Test
    void busModeWiredFromSpringConfig() {
        assertThat(pathSelector.isBus()).isTrue();
        assertThat(pathSelector.mode()).isEqualTo(PathMode.BUS);
    }
}
