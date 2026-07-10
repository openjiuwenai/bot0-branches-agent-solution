package com.openjiuwen.rdc.spi.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceIdCodecTest {

    @Test
    void derive_returns_host_port_with_explicit_port() {
        assertThat(InstanceIdCodec.derive("http://10.0.0.1:8080")).isEqualTo("10.0.0.1-8080");
        assertThat(InstanceIdCodec.derive("https://host:8443")).isEqualTo("host-8443");
    }

    @Test
    void derive_fills_scheme_default_port_when_omitted() {
        assertThat(InstanceIdCodec.derive("http://host")).isEqualTo("host-80");
        assertThat(InstanceIdCodec.derive("https://secure.host")).isEqualTo("secure.host-443");
    }

    @Test
    void derive_assumes_http_when_no_scheme() {
        assertThat(InstanceIdCodec.derive("host:8080")).isEqualTo("host-8080");
    }

    @Test
    void derive_lowercases_host() {
        assertThat(InstanceIdCodec.derive("http://HOST:8080")).isEqualTo("host-8080");
    }

    @Test
    void derive_rejects_blank_url() {
        assertThatThrownBy(() -> InstanceIdCodec.derive(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointUrl");
    }

    @Test
    void applyTo_stamps_host_port_instance_id_onto_entry() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setEndpointUrl("http://10.0.0.1:8080");
        InstanceIdCodec.applyTo(entry);
        assertThat(entry.getInstanceId()).isEqualTo("10.0.0.1-8080");
    }
}
