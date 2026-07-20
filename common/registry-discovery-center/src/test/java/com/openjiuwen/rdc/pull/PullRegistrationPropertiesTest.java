/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.pull;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.model.FrameworkType;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PullRegistrationProperties} — default values +
 * binding from {@code rdc.pull-registration.*} config tree (REQ-2026-004).
 */
class PullRegistrationPropertiesTest {

    @Test
    void defaults_disabled_with_empty_runtimes() {
        PullRegistrationProperties props = new PullRegistrationProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getRuntimes()).isEmpty();
    }

    @Test
    void runtime_entry_defaults_card_path_and_route_key() {
        PullRegistrationProperties.RuntimeEntry entry = new PullRegistrationProperties.RuntimeEntry();
        assertThat(entry.getCardPath()).isEqualTo("/.well-known/agent-card.json");
        assertThat(entry.getRouteKey()).isEqualTo("/v1/query");
        assertThat(entry.getContractVersion()).isEqualTo("v1");
        assertThat(entry.getCapabilityVersion()).isEqualTo("v1");
    }

    @Test
    void runtime_entry_setters_round_trip() {
        PullRegistrationProperties.RuntimeEntry entry = new PullRegistrationProperties.RuntimeEntry();
        entry.setBaseUrl("http://localhost:8090");
        entry.setTenantId("tenant-A");
        entry.setAgentId("agent-001");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRegion("cn-east-1");

        assertThat(entry.getBaseUrl()).isEqualTo("http://localhost:8090");
        assertThat(entry.getTenantId()).isEqualTo("tenant-A");
        assertThat(entry.getAgentId()).isEqualTo("agent-001");
        assertThat(entry.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
        assertThat(entry.getRegion()).isEqualTo("cn-east-1");
    }

    @Test
    void http_timeouts_are_code_defaulted_to_5s_connect_10s_read() {
        // OQ-3 H2: connect 5s + read 10s, code-defaulted, not exposed as config keys.
        assertThat(PullRegistrationProperties.CONNECT_TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(PullRegistrationProperties.READ_TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(10));
    }
}
