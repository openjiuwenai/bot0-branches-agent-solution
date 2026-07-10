/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Round-trip + baseline-breaking rejection tests for {@link RouteHandleCodec}
 * (ADR-0160 decision 3, HD3-006 opaque route handle).
 *
 * <p>Covers:
 * <ul>
 *   <li><b>RB-F1</b> — encode → decode round-trip preserves all 6 fields
 *       (FEAT-016: {@code instanceId} added as 4th field).</li>
 *   <li><b>RB-F2</b> — {@code v2:} prefix is now the <em>only</em> accepted
 *       format (FEAT-016 baseline-breaking). Old {@code v1:} 5-field and
 *       no-prefix 4-field handles are rejected with
 *       {@link IllegalArgumentException}. Extra fields in the v2: JSON are
 *       ignored so phase-2 encoders can extend the format forward-compatibly.</li>
 *   <li><b>RB-F3</b> — malformed handles (bad base64, bad JSON, missing
 *       fields, blank, missing {@code v2:} prefix) raise
 *       {@link IllegalArgumentException}.</li>
 *   <li><b>RB-F4</b> — encode rejects blank inputs (including
 *       {@code instanceId}).</li>
 * </ul>
 *
 * <p>Authority: ADR-0160 decision 3 + ICD-Agent-Registry-Discovery HD3-006 +
 * FEAT-016 (v2: 6-field + instanceId; baseline-breaking decode). The
 * {@code req-2026-003-route-handle-codec-v1-prefix-decode} post-edit gate
 * rule prevents accidental removal of the {@code v2:} prefix recognition.
 */
class RouteHandleCodecTest {
    private static final String TENANT = "tenant-A";
    private static final String AGENT = "agent-001";
    private static final String SERVICE_ID = "wealth-svc";
    private static final String INSTANCE_ID = "test-host-8080";
    private static final String ROUTE_KEY = "rk://svc/default";
    private static final String CONTRACT = "1.0.0";

    // ---- RB-F1: round-trip ------------------------------------------------

    @Test
    void encode_then_decode_round_trips_all_six_fields() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));

        RouteHandleCodec.HandleFields decoded = RouteHandleCodec.decode(handle);

        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.agentId()).isEqualTo(AGENT);
        assertThat(decoded.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(decoded.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(decoded.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(decoded.contractVersion()).isEqualTo(CONTRACT);
    }

    @Test
    void encode_then_decode_round_trips_unicode_payload() {
        String tenant = "租户-α";
        String agent = "智能体-001";
        String serviceId = "财务服务";
        String instanceId = "实例-α-8080";
        String routeKey = "rk://服务/默认";
        String contract = "v2-β";

        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                tenant, agent, serviceId, instanceId, routeKey, contract));
        RouteHandleCodec.HandleFields decoded = RouteHandleCodec.decode(handle);

        assertThat(decoded.tenantId()).isEqualTo(tenant);
        assertThat(decoded.agentId()).isEqualTo(agent);
        assertThat(decoded.serviceId()).isEqualTo(serviceId);
        assertThat(decoded.instanceId()).isEqualTo(instanceId);
        assertThat(decoded.routeKey()).isEqualTo(routeKey);
        assertThat(decoded.contractVersion()).isEqualTo(contract);
    }

    @Test
    void encode_always_produces_v2_prefix() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));
        assertThat(handle)
                .as("FEAT-016: encode always produces the v2: prefix (6-field format)")
                .startsWith(RouteHandleCodec.V2_PREFIX);
    }

    @Test
    void encoded_payload_after_v2_prefix_is_base64_text() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));
        assertThat(handle).startsWith(RouteHandleCodec.V2_PREFIX);
        String payload = handle.substring(RouteHandleCodec.V2_PREFIX.length());
        assertThat(payload)
                .as("payload after v2: prefix is base64 text")
                .matches("[A-Za-z0-9+/]+={0,2}");
    }

    // ---- RB-F2: v2: prefix is the only accepted format -------------------

    @Test
    void decode_accepts_v2_prefix_and_ignores_extra_fields() {
        // Simulate a phase-2 encoder: v2: prefix + extra consul fields.
        java.util.Map<String, Object> phase2Fields = new java.util.LinkedHashMap<>();
        phase2Fields.put("tenantId", TENANT);
        phase2Fields.put("agentId", AGENT);
        phase2Fields.put("serviceId", SERVICE_ID);
        phase2Fields.put("instanceId", INSTANCE_ID);
        phase2Fields.put("routeKey", ROUTE_KEY);
        phase2Fields.put("contractVersion", CONTRACT);
        phase2Fields.put("consulAddr", "consul.example");
        phase2Fields.put("consulPort", 8500);
        String phase2Handle = encodeV2WithExtraFields(phase2Fields);

        RouteHandleCodec.HandleFields decoded = RouteHandleCodec.decode(phase2Handle);

        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.agentId()).isEqualTo(AGENT);
        assertThat(decoded.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(decoded.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(decoded.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(decoded.contractVersion()).isEqualTo(CONTRACT);
    }

    /**
     * FEAT-016 baseline-breaking: decode NO LONGER accepts old {@code v1:}
     * 5-field handles (REQ-2026-006 format). Pre-FEAT-016 handles are
     * rejected with {@link IllegalArgumentException} → HTTP 400
     * {@code malformed_handle}. The routeHandle lifetime is short (one
     * health-probe cycle); after migration the caller re-fetches
     * {@code GET /instances} to get new v2: handles. No rolling-compat window
     * is maintained (H2-4 one-time coupling).
     */
    @Test
    void decode_rejects_old_v1_prefix_format() {
        // Hand-encode a v1: 5-field handle (pre-FEAT-016 REQ-2026-006 format).
        String oldHandle = encodeLegacyV1Prefix(TENANT, AGENT, SERVICE_ID, ROUTE_KEY, CONTRACT);

        assertThatThrownBy(() -> RouteHandleCodec.decode(oldHandle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2:");
    }

    /**
     * FEAT-016 baseline-breaking: decode NO LONGER accepts old no-prefix
     * 4-field handles (pre-REQ-2026-006 format). Rejected with
     * {@link IllegalArgumentException} → HTTP 400 {@code malformed_handle}.
     */
    @Test
    void decode_rejects_old_no_prefix_format() {
        // Hand-encode a no-prefix 4-field handle (pre-REQ-2026-006 format).
        String oldHandle = encodeLegacyNoPrefix(TENANT, AGENT, ROUTE_KEY, CONTRACT);

        assertThatThrownBy(() -> RouteHandleCodec.decode(oldHandle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2:");
    }

    // ---- RB-F3: malformed handle rejection -------------------------------

    @Test
    void decode_rejects_null_handle() {
        assertThatThrownBy(() -> RouteHandleCodec.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handle");
    }

    @Test
    void decode_rejects_blank_handle() {
        assertThatThrownBy(() -> RouteHandleCodec.decode("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handle");
    }

    @Test
    void decode_rejects_invalid_base64_after_v2_prefix() {
        assertThatThrownBy(() -> RouteHandleCodec.decode(RouteHandleCodec.V2_PREFIX + "!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void decode_rejects_valid_base64_that_is_not_json() {
        String notJson = java.util.Base64.getEncoder()
                .encodeToString("not a json object".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(RouteHandleCodec.V2_PREFIX + notJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void decode_rejects_json_missing_required_field() {
        // Build a JSON missing contractVersion.
        String json = "{\"tenantId\":\"t\",\"agentId\":\"a\",\"serviceId\":\"s\","
                + "\"instanceId\":\"i\",\"routeKey\":\"rk\"}";
        String handle = RouteHandleCodec.V2_PREFIX + java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contractVersion");
    }

    @Test
    void decode_rejects_json_with_blank_required_field() {
        String json = "{\"tenantId\":\"t\",\"agentId\":\"\",\"serviceId\":\"s\","
                + "\"instanceId\":\"i\",\"routeKey\":\"rk\",\"contractVersion\":\"c\"}";
        String handle = RouteHandleCodec.V2_PREFIX + java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    void decode_rejects_json_missing_instance_id_field() {
        // FEAT-016: instanceId is a required field. A JSON missing it
        // (e.g. a pre-FEAT-016 v1: 5-field JSON wrapped in v2:) is rejected.
        String json = "{\"tenantId\":\"t\",\"agentId\":\"a\",\"serviceId\":\"s\","
                + "\"routeKey\":\"rk\",\"contractVersion\":\"c\"}";
        String handle = RouteHandleCodec.V2_PREFIX + java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }

    // ---- RB-F4: encode rejects blank inputs ------------------------------

    @Test
    void encode_rejects_null_tenant() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                null, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void encode_rejects_blank_agent() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, "  ", SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    void encode_rejects_blank_service_id() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, "  ", INSTANCE_ID, ROUTE_KEY, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void encode_rejects_blank_instance_id() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, "  ", ROUTE_KEY, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }

    @Test
    void encode_rejects_null_route_key() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, null, CONTRACT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routeKey");
    }

    @Test
    void encode_rejects_blank_contract_version() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contractVersion");
    }

    // ---- helpers: simulate phase-2 / legacy encoders ---------------------

    /**
     * Hand-encode a {@code v2:} prefixed handle that includes the 6 FEAT-016
     * fields plus two phase-2 extra fields ({@code consulAddr},
     * {@code consulPort}). Mirrors what a phase-2 Consul-backed encoder would
     * produce. MVP decode MUST accept and ignore the extras.
     *
     * @param fields the JSON fields to encode (tenantId, agentId, serviceId,
     *               instanceId, routeKey, contractVersion, consulAddr,
     *               consulPort)
     * @return the {@code v2:} + base64(JSON) encoded handle
     */
    private static String encodeV2WithExtraFields(java.util.Map<String, Object> fields) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            fields.forEach((key, value) -> {
                if (value instanceof Integer) {
                    node.put(key, (Integer) value);
                } else {
                    node.put(key, String.valueOf(value));
                }
            });
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(node);
            String base64 = java.util.Base64.getEncoder().encodeToString(json);
            return RouteHandleCodec.V2_PREFIX + base64;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Hand-encode a pre-FEAT-016 {@code v1:} prefixed 5-field handle (the
     * REQ-2026-006 format). FEAT-016 baseline-breaking: decode now rejects
     * this format (the v1: prefix is no longer recognized).
     *
     * @param tenantId        the tenant id
     * @param agentId         the agent id
     * @param serviceId       the service id
     * @param routeKey        the route key
     * @param contractVersion the contract version
     * @return the {@code v1:} + base64(JSON) encoded handle
     */
    private static String encodeLegacyV1Prefix(String tenantId, String agentId, String serviceId,
                                               String routeKey, String contractVersion) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("serviceId", serviceId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(node);
            String base64 = java.util.Base64.getEncoder().encodeToString(json);
            return "v1:" + base64;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Hand-encode a pre-REQ-2026-006 no-prefix 4-field handle (the format
     * decode used to accept). FEAT-016 baseline-breaking: decode now rejects
     * this format.
     *
     * @param tenantId        the tenant id
     * @param agentId         the agent id
     * @param routeKey        the route key
     * @param contractVersion the contract version
     * @return the base64(JSON) encoded handle (no prefix)
     */
    private static String encodeLegacyNoPrefix(String tenantId, String agentId,
                                               String routeKey, String contractVersion) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(node);
            return java.util.Base64.getEncoder().encodeToString(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
