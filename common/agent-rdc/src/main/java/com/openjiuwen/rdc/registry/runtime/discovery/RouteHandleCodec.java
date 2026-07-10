package com.openjiuwen.rdc.registry.runtime.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encode / decode opaque route handles (ADR-0160 decision 3, HD3-006).
 *
 * <p>The route handle is the <b>only</b> reference an
 * {@link com.openjiuwen.rdc.spi.registry.AgentDiscoveryService} caller
 * receives — it never sees the physical {@code endpointUrl} or
 * {@code routeKey} in plain form. The forwarding layer recovers them via
 * {@code AgentDiscoveryService.resolveRouteHandle(handle, tenantId)}, which
 * internally calls {@link #decode(String)} and then asks the repository for
 * the endpoint. The codec stays internal to {@code registry.runtime.discovery};
 * the encoding format evolved in FEAT-016 without breaking the SPI contract
 * (the handle is opaque to callers).
 *
 * <h2>FEAT-016 format (v2: 6-field, baseline-breaking)</h2>
 * <pre>
 *   encode(tenantId, agentId, serviceId, instanceId, routeKey, contractVersion)
 *     = "v2:" + base64( JSON{tenantId, agentId, serviceId, instanceId, routeKey, contractVersion} )
 * </pre>
 * Six fields, {@code v2:} prefix. The {@code serviceId} field is the
 * caller-overridable logical service identifier (host only); the
 * {@code instanceId} field is the server-derived host-port instance id (from
 * {@code InstanceIdCodec}). Together they let the forwarding layer resolve a
 * specific instance among N replicas of the same {@code agentId} /
 * {@code serviceId}.
 *
 * <h2>Baseline-breaking decode (old v1: 5-field and no-prefix 4-field rejected)</h2>
 * <p>FEAT-016 removes forward-compatibility with the REQ-2026-006
 * {@code v1:} 5-field format and the pre-REQ-2026-006 no-prefix 4-field
 * format. Old handles (produced before FEAT-016 deployment) are rejected
 * with {@link IllegalArgumentException} → HTTP 400
 * {@code malformed_handle}. The routeHandle lifetime is short (one
 * health-probe cycle); after migration the caller re-fetches
 * {@code GET /instances} to get new v2: handles. No rolling-compat window
 * is maintained (H2-4 one-time coupling decision).
 *
 * <p>Malformed handles (bad base64, missing JSON fields, missing {@code v2:}
 * prefix, unknown prefix) raise {@link IllegalArgumentException} — the
 * {@code AgentDiscoveryService.resolveRouteHandle} SPI contract maps that to
 * HTTP 400 {@code malformed_handle}. Tenant mismatch (the decoded
 * {@code tenantId} does not equal the caller's tenant) is detected by the
 * caller (discovery service) and raises
 * {@link com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException}
 * (HTTP 400 {@code tenant_isolation_violation}).
 *
 * <p>Pure functions on Jackson + {@code java.util.Base64} — no Spring / JDBC
 * imports. The {@code discovery} subpackage is permitted Jackson by the
 * {@code req-2026-003-jdbc-confined-to-persistence} gate (which forbids only
 * JDBC); the SPI-purity gate ({@code req-2026-003-spi-registry-no-jackson})
 * confines Jackson to runtime packages only.
 *
 * <p>Authority: ADR-0160 decision 3 + HD3-006 (opaque route handle) +
 * REQ-2026-006 (v1: 5-field + serviceId) + FEAT-016 (v2: 6-field + instanceId).
 */
final class RouteHandleCodec {

    /**
     * Version-2 prefix marker. FEAT-016 encode always produces this prefix;
     * decode requires it — old v1: / no-prefix handles are rejected.
     */
    static final String V2_PREFIX = "v2:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RouteHandleCodec() {
        // utility class — no instances
    }

    /**
     * Encode the 6 fields into an opaque handle. Format:
     * {@code "v2:" + base64(JSON{tenantId, agentId, serviceId, instanceId,
     * routeKey, contractVersion})}.
     *
     * @param tenantId        registry-key tenant dimension (HD3-003)
     * @param agentId         registry-key agent dimension
     * @param serviceId       logical service identifier (host only, caller-overridable)
     * @param instanceId      server-derived host-port instance id (from
     *                        {@code InstanceIdCodec.derive(endpointUrl)}) —
     *                        distinguishes concrete instances under the same serviceId
     * @param routeKey        logical routing key the forwarding layer uses to
     *                        address the agent
     * @param contractVersion contract version the agent pinned at registration
     * @return opaque route handle with {@code v2:} prefix; never {@code null}
     */
    static String encode(String tenantId, String agentId, String serviceId,
                         String instanceId, String routeKey, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(instanceId, "instanceId");
        requireNonBlank(routeKey, "routeKey");
        requireNonBlank(contractVersion, "contractVersion");
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("serviceId", serviceId);
            node.put("instanceId", instanceId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = MAPPER.writeValueAsBytes(node);
            return V2_PREFIX + Base64.getEncoder().encodeToString(json);
        } catch (Exception ex) {
            // Jackson failures on plain String values are not recoverable —
            // wrap as IllegalArgumentException per SPI contract.
            throw new IllegalArgumentException("failed to encode route handle", ex);
        }
    }

    /**
     * Decode an opaque handle into the 6 fields. FEAT-016: only accepts
     * {@code v2:} prefix format; old {@code v1:} 5-field and no-prefix 4-field
     * handles are rejected with {@link IllegalArgumentException}
     * (baseline-breaking).
     *
     * @param handle opaque handle produced by {@link #encode}
     * @return decoded 6-field view; never {@code null}
     * @throws IllegalArgumentException if the handle is malformed (no
     *         {@code v2:} prefix, bad base64, bad JSON, missing fields)
     */
    static DecodedHandle decode(String handle) {
        requireNonBlank(handle, "handle");
        String payload = stripPrefix(handle);
        JsonNode node = parseJson(payload);
        return new DecodedHandle(
                requireTextField(node, "tenantId"),
                requireTextField(node, "agentId"),
                requireTextField(node, "serviceId"),
                requireTextField(node, "instanceId"),
                requireTextField(node, "routeKey"),
                requireTextField(node, "contractVersion"));
    }

    /**
     * Strip the {@code v2:} prefix. FEAT-016: handles WITHOUT the prefix (or
     * with the old {@code v1:} prefix) are rejected as malformed
     * (baseline-breaking — old v1: / no-prefix formats rejected since
     * FEAT-016).
     */
    private static String stripPrefix(String handle) {
        if (!handle.startsWith(V2_PREFIX)) {
            throw new IllegalArgumentException(
                    "route handle missing v2: prefix (old v1: / no-prefix formats rejected since FEAT-016)");
        }
        return handle.substring(V2_PREFIX.length());
    }

    private static JsonNode parseJson(String base64Payload) {
        byte[] json;
        try {
            json = Base64.getDecoder().decode(base64Payload);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("route handle is not valid base64", ex);
        }
        try {
            return MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalArgumentException("route handle is not valid JSON", ex);
        }
    }

    private static String requireTextField(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "route handle missing required field: " + name);
        }
        return field.asText();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Decoded view of a route handle — the 6 FEAT-016 fields. The
     * {@code serviceId} field is the logical service identifier; the
     * {@code instanceId} field lets the forwarding layer resolve a specific
     * instance among N replicas.
     */
    record DecodedHandle(String tenantId, String agentId, String serviceId,
                         String instanceId, String routeKey, String contractVersion) {
    }
}
