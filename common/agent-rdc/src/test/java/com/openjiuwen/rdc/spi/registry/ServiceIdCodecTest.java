/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.spi.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceIdCodec} — host-only {@code serviceId}
 * derivation (FEAT-016 phase one: serviceId semantics changed from
 * "host-port" to "host only").
 *
 * @since 2026-07-10
 */
class ServiceIdCodecTest {
    @Test
    void derive_returns_host_only_without_port() {
        assertThat(ServiceIdCodec.derive("http://10.0.0.1:8080")).isEqualTo("10.0.0.1");
        assertThat(ServiceIdCodec.derive("https://host.example.com:443")).isEqualTo("host.example.com");
    }

    @Test
    void derive_lowercases_host() {
        assertThat(ServiceIdCodec.derive("http://HOST.Example.COM:8080")).isEqualTo("host.example.com");
    }

    @Test
    void derive_omits_port_when_url_has_no_explicit_port() {
        assertThat(ServiceIdCodec.derive("http://host")).isEqualTo("host");
        assertThat(ServiceIdCodec.derive("https://secure.host")).isEqualTo("secure.host");
    }

    @Test
    void derive_rejects_blank_url() {
        assertThatThrownBy(() -> ServiceIdCodec.derive("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointUrl");
    }

    @Test
    void derive_rejects_url_without_host() {
        assertThatThrownBy(() -> ServiceIdCodec.derive("file:///local/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void applyTo_stamps_host_only_service_id_onto_entry() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setEndpointUrl("http://10.0.0.1:8080");
        ServiceIdCodec.applyTo(entry);
        assertThat(entry.getServiceId()).isEqualTo("10.0.0.1");
    }
}
