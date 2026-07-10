/*
 * Copyright (C) 2026 Huawei Technologies Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openjiuwen.rdc.spi.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

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
