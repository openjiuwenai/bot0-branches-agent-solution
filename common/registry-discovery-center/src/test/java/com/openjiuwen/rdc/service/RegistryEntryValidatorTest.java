/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.RegistryEntryInvalidException;

import org.junit.jupiter.api.Test;

/**
 * RegistryEntryValidatorTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class RegistryEntryValidatorTest {
    @Test
    void missing_endpoint_raises_registry_entry_invalid() {
        AgentRegistryEntry entry = validEntry();
        entry.setEndpointUrl(null);

        assertThatThrownBy(() -> RegistryEntryValidator.validate(entry, "trace-1"))
                .isInstanceOf(RegistryEntryInvalidException.class)
                .satisfies(ex -> assertThatFailureCode(ex, "REGISTRY_ENTRY_INVALID"));
    }

    @Test
    void invalid_endpoint_scheme_raises_registry_entry_invalid() {
        AgentRegistryEntry entry = validEntry();
        entry.setEndpointUrl("ftp://bad.example/agent");

        assertThatThrownBy(() -> RegistryEntryValidator.validate(entry, "trace-1"))
                .isInstanceOf(RegistryEntryInvalidException.class);
    }

    private static AgentRegistryEntry validEntry() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setTenantId("tenant-A");
        entry.setAgentId("agent-1");
        entry.setAgentName("demo");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRouteKey("rk://svc/default");
        entry.setContractVersion("1.0.0");
        entry.setCapabilityVersion("1.0.0");
        entry.setEndpointUrl("https://agent.example/agent");
        return entry;
    }

    private static void assertThatFailureCode(Throwable ex, String code) {
        org.assertj.core.api.Assertions.assertThat(ex).isInstanceOf(RegistryEntryInvalidException.class);
        if (ex instanceof RegistryEntryInvalidException invalid) {
            org.assertj.core.api.Assertions.assertThat(invalid.failure().failureCode()).isEqualTo(code);
        }
    }
}
