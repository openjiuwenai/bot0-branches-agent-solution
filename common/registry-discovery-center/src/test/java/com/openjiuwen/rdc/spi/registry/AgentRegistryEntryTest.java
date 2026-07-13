/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.spi.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link AgentRegistryEntry} capabilities getter/setter.
 *
 * @since 2026-07-10
 */
class AgentRegistryEntryTest {
    @Test
    void capabilities_getter_setter_round_trip() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setCapabilities(List.of("wealth.purchase", "wealth.assessment"));
        assertThat(entry.getCapabilities()).containsExactly("wealth.purchase", "wealth.assessment");
    }

    @Test
    void capabilities_defaults_to_null_when_not_set() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        assertThat(entry.getCapabilities()).isNull();
    }
}
