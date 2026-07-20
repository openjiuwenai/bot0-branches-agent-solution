/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Round-trip + baseline-breaking rejection tests for {@link RouteHandleCodec}
 * (FEAT-016 v2: 6-field opaque route handle).
 *
 * @since 0.1.0 (2026)
 */
class RouteHandleCodecTest {
    private static final String TENANT = "tenant-A";
    private static final String AGENT = "agent-001";
    private static final String SERVICE_ID = "test-host";
    private static final String INSTANCE_ID = "test-host-8080";
    private static final String ROUTE_KEY = "rk://svc/default";
    private static final String CONTRACT = "1.0.0";

    private static RouteHandleCodec.HandleFields fields() {
        return new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT);
    }

    @Test
    void encode_then_decode_round_trips_all_six_fields() {
        String handle = RouteHandleCodec.encode(fields());
        RouteHandleCodec.HandleFields decoded = RouteHandleCodec.decode(handle);
        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.agentId()).isEqualTo(AGENT);
        assertThat(decoded.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(decoded.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(decoded.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(decoded.contractVersion()).isEqualTo(CONTRACT);
    }

    @Test
    void encode_always_produces_v2_prefix() {
        assertThat(RouteHandleCodec.encode(fields())).startsWith(RouteHandleCodec.V2_PREFIX);
    }
    @Test
    void decode_rejects_old_v1_prefix_format() {
        String json = "{\"tenantId\":\"t\",\"agentId\":\"a\",\"serviceId\":\"s\","
                + "\"routeKey\":\"rk\",\"contractVersion\":\"c\"}";
        String handle = "v1:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2:");
    }

    @Test
    void decode_rejects_missing_instance_id_field() {
        String json = "{\"tenantId\":\"t\",\"agentId\":\"a\",\"serviceId\":\"s\","
                + "\"routeKey\":\"rk\",\"contractVersion\":\"c\"}";
        String handle = RouteHandleCodec.V2_PREFIX
                + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }

    @Test
    void encode_rejects_blank_instance_id() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, "  ", ROUTE_KEY, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }
}
