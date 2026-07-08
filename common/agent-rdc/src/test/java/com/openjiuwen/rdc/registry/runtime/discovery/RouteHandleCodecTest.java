package com.openjiuwen.rdc.registry.runtime.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip + forward-compatibility tests for {@link RouteHandleCodec}
 * (ADR-0160 decision 3, HD3-006 opaque route handle).
 *
 * <p>Covers:
 * <ul>
 *   <li><b>RB-F1</b> — encode → decode round-trip preserves all 4 fields.</li>
 *   <li><b>RB-F2</b> — {@code v1:} prefix (phase-2 format) is accepted by
 *       decode; extra fields ({@code consulAddr} / {@code consulPort}) are
 *       ignored so the rolling-migration window is safe.</li>
 *   <li><b>RB-F3</b> — malformed handles (bad base64, bad JSON, missing
 *       fields, blank) raise {@link IllegalArgumentException}.</li>
 *   <li><b>RB-F4</b> — encode rejects blank inputs.</li>
 * </ul>
 *
 * <p>Authority: ADR-0160 decision 3 + ICD-Agent-Registry-Discovery HD3-006.
 * The {@code req-2026-003-route-handle-codec-v1-prefix-decode} post-edit gate
 * rule prevents accidental removal of the {@code v1:} prefix recognition.
 */
class RouteHandleCodecTest {

    private static final String TENANT = "tenant-A";
    private static final String AGENT = "agent-001";
    private static final String ROUTE_KEY = "rk://svc/default";
    private static final String CONTRACT = "1.0.0";

    // ---- RB-F1: round-trip ------------------------------------------------

    @Test
    void encode_then_decode_round_trips_all_four_fields() {
        String handle = RouteHandleCodec.encode(TENANT, AGENT, ROUTE_KEY, CONTRACT);

        RouteHandleCodec.DecodedHandle decoded = RouteHandleCodec.decode(handle);

        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.agentId()).isEqualTo(AGENT);
        assertThat(decoded.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(decoded.contractVersion()).isEqualTo(CONTRACT);
    }

    @Test
    void encode_then_decode_round_trips_unicode_payload() {
        String tenant = "租户-α";
        String agent = "智能体-001";
        String routeKey = "rk://服务/默认";
        String contract = "v2-β";

        String handle = RouteHandleCodec.encode(tenant, agent, routeKey, contract);
        RouteHandleCodec.DecodedHandle decoded = RouteHandleCodec.decode(handle);

        assertThat(decoded.tenantId()).isEqualTo(tenant);
        assertThat(decoded.agentId()).isEqualTo(agent);
        assertThat(decoded.routeKey()).isEqualTo(routeKey);
        assertThat(decoded.contractVersion()).isEqualTo(contract);
    }

    @Test
    void encode_produces_no_v1_prefix_in_mvp() {
        String handle = RouteHandleCodec.encode(TENANT, AGENT, ROUTE_KEY, CONTRACT);
        assertThat(handle)
                .as("MVP encode never produces the v1: prefix (ADR-0160 decision 3)")
                .doesNotStartWith(RouteHandleCodec.V1_PREFIX);
    }

    @Test
    void encoded_handle_is_base64_text() {
        String handle = RouteHandleCodec.encode(TENANT, AGENT, ROUTE_KEY, CONTRACT);
        assertThat(handle).matches("[A-Za-z0-9+/]+={0,2}");
    }

    // ---- RB-F2: v1: prefix forward compatibility --------------------------

    @Test
    void decode_accepts_v1_prefix_and_ignores_extra_fields() {
        // Simulate a phase-2 encoder: v1: prefix + extra consul fields.
        String phase2Handle = encodeV1WithExtraFields(TENANT, AGENT, ROUTE_KEY, CONTRACT,
                "consul.example", 8500);

        RouteHandleCodec.DecodedHandle decoded = RouteHandleCodec.decode(phase2Handle);

        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.agentId()).isEqualTo(AGENT);
        assertThat(decoded.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(decoded.contractVersion()).isEqualTo(CONTRACT);
    }

    @Test
    void decode_accepts_both_prefixed_and_unprefixed_forms() {
        String mvpHandle = RouteHandleCodec.encode(TENANT, AGENT, ROUTE_KEY, CONTRACT);
        String v1Handle = RouteHandleCodec.V1_PREFIX + mvpHandle;

        RouteHandleCodec.DecodedHandle fromMvp = RouteHandleCodec.decode(mvpHandle);
        RouteHandleCodec.DecodedHandle fromV1 = RouteHandleCodec.decode(v1Handle);

        assertThat(fromMvp).isEqualTo(fromV1);
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
    void decode_rejects_invalid_base64() {
        assertThatThrownBy(() -> RouteHandleCodec.decode("!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void decode_rejects_valid_base64_that_is_not_json() {
        String notJson = java.util.Base64.getEncoder()
                .encodeToString("not a json object".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(notJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void decode_rejects_json_missing_required_field() {
        // Build a JSON missing contractVersion.
        String json = "{\"tenantId\":\"t\",\"agentId\":\"a\",\"routeKey\":\"rk\"}";
        String handle = java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contractVersion");
    }

    @Test
    void decode_rejects_json_with_blank_required_field() {
        String json = "{\"tenantId\":\"t\",\"agentId\":\"\",\"routeKey\":\"rk\",\"contractVersion\":\"c\"}";
        String handle = java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RouteHandleCodec.decode(handle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    // ---- RB-F4: encode rejects blank inputs ------------------------------

    @Test
    void encode_rejects_null_tenant() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(null, AGENT, ROUTE_KEY, CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void encode_rejects_blank_agent() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(TENANT, "  ", ROUTE_KEY, CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    void encode_rejects_null_route_key() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(TENANT, AGENT, null, CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routeKey");
    }

    @Test
    void encode_rejects_blank_contract_version() {
        assertThatThrownBy(() -> RouteHandleCodec.encode(TENANT, AGENT, ROUTE_KEY, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contractVersion");
    }

    // ---- helper: simulate a phase-2 encoder ------------------------------

    /**
     * Hand-encode a {@code v1:} prefixed handle that includes the 4 MVP
     * fields plus two phase-2 extra fields ({@code consulAddr},
     * {@code consulPort}). Mirrors what a phase-2 Consul-backed encoder
     * would produce. MVP decode MUST accept and ignore the extras.
     */
    private static String encodeV1WithExtraFields(String tenantId, String agentId,
                                                  String routeKey, String contractVersion,
                                                  String consulAddr, int consulPort) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            node.put("consulAddr", consulAddr);
            node.put("consulPort", consulPort);
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(node);
            String base64 = java.util.Base64.getEncoder().encodeToString(json);
            return RouteHandleCodec.V1_PREFIX + base64;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
