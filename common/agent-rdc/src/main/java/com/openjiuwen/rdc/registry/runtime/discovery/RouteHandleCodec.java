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
 * the encoding format evolved in REQ-2026-006 without breaking the SPI
 * contract (the handle is opaque to callers).
 *
 * <h2>REQ-2026-006 format (v1: 5-field, baseline-breaking)</h2>
 * <pre>
 *   encode(tenantId, agentId, serviceId, routeKey, contractVersion)
 *     = "v1:" + base64( JSON{tenantId, agentId, serviceId, routeKey, contractVersion} )
 * </pre>
 * Five fields, {@code v1:} prefix. The {@code serviceId} field is the
 * server-derived instance id (from {@code ServiceIdCodec}) — it lets the
 * forwarding layer resolve a specific instance among N replicas of the same
 * {@code agentId}.
 *
 * <h2>Baseline-breaking decode (old 4-field handles rejected)</h2>
 * <p>REQ-2026-006 removes forward-compatibility with the pre-existing
 * no-prefix 4-field format. Old handles (produced before REQ-2026-006
 * deployment) are rejected with {@link IllegalArgumentException} → HTTP 400
 * {@code malformed_handle}. The routeHandle lifetime is short (one
 * health-probe cycle); after migration the caller re-fetches
 * {@code GET /instances} to get new v1: handles. No rolling-compat window
 * is maintained (H2-4 one-time coupling decision).
 *
 * <p>Malformed handles (bad base64, missing JSON fields, missing {@code v1:}
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
 * REQ-2026-006 (v1: 5-field + serviceId).
 */
final class RouteHandleCodec {

    /**
     * Version-1 prefix marker. REQ-2026-006 encode always produces this
     * prefix; decode requires it — old no-prefix handles are rejected.
     */
    static final String V1_PREFIX = "v1:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RouteHandleCodec() {
        // utility class — no instances
    }

    /**
     * Encode the 5 fields into an opaque handle. Format:
     * {@code "v1:" + base64(JSON{tenantId, agentId, serviceId, routeKey,
     * contractVersion})}.
     *
     * @param tenantId        registry-key tenant dimension (HD3-003)
     * @param agentId         registry-key agent dimension
     * @param serviceId       server-derived instance id (from
     *                        {@code ServiceIdCodec.derive(endpointUrl)}) —
     *                        the third PK column that distinguishes N
     *                        instances of the same agentId
     * @param routeKey        logical routing key the forwarding layer uses to
     *                        address the agent
     * @param contractVersion contract version the agent pinned at registration
     * @return opaque route handle with {@code v1:} prefix; never {@code null}
     */
    static String encode(String tenantId, String agentId, String serviceId,
                         String routeKey, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(routeKey, "routeKey");
        requireNonBlank(contractVersion, "contractVersion");
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("serviceId", serviceId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = MAPPER.writeValueAsBytes(node);
            return V1_PREFIX + Base64.getEncoder().encodeToString(json);
        } catch (Exception ex) {
            // Jackson failures on plain String values are not recoverable —
            // wrap as IllegalArgumentException per SPI contract.
            throw new IllegalArgumentException("failed to encode route handle", ex);
        }
    }

    /**
     * Decode an opaque handle into the 5 fields. REQ-2026-006: only accepts
     * {@code v1:} prefix format; old no-prefix 4-field handles are rejected
     * with {@link IllegalArgumentException} (baseline-breaking).
     *
     * @param handle opaque handle produced by {@link #encode}
     * @return decoded 5-field view; never {@code null}
     * @throws IllegalArgumentException if the handle is malformed (no
     *         {@code v1:} prefix, bad base64, bad JSON, missing fields)
     */
    static DecodedHandle decode(String handle) {
        requireNonBlank(handle, "handle");
        String payload = stripPrefix(handle);
        JsonNode node = parseJson(payload);
        return new DecodedHandle(
                requireTextField(node, "tenantId"),
                requireTextField(node, "agentId"),
                requireTextField(node, "serviceId"),
                requireTextField(node, "routeKey"),
                requireTextField(node, "contractVersion"));
    }

    /**
     * Strip the {@code v1:} prefix. REQ-2026-006: handles WITHOUT the prefix
     * are rejected as malformed (baseline-breaking — old 4-field handles no
     * longer accepted).
     */
    private static String stripPrefix(String handle) {
        if (!handle.startsWith(V1_PREFIX)) {
            throw new IllegalArgumentException(
                    "route handle missing v1: prefix (old format rejected since REQ-2026-006)");
        }
        return handle.substring(V1_PREFIX.length());
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
     * Decoded view of a route handle — the 5 REQ-2026-006 fields. The
     * {@code serviceId} field lets the forwarding layer resolve a specific
     * instance among N replicas.
     */
    record DecodedHandle(String tenantId, String agentId, String serviceId,
                         String routeKey, String contractVersion) {
    }
}
