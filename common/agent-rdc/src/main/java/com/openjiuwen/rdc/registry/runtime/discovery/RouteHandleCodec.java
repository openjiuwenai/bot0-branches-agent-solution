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
 * the encoding format can evolve ({@code v1:} prefix in phase 2) without
 * breaking cross-module consumers.
 *
 * <h2>MVP encode (no prefix)</h2>
 * <pre>
 *   encode(tenantId, agentId, routeKey, contractVersion)
 *     = base64( JSON{tenantId, agentId, routeKey, contractVersion} )
 * </pre>
 * Four fields, no version prefix. The JSON keys are stable across versions
 * (phase 2 only <em>adds</em> {@code consulAddr} / {@code consulPort}).
 *
 * <h2>MVP decode (forward-compatible)</h2>
 * Decode recognizes two formats:
 * <ul>
 *   <li><b>no prefix</b> — MVP format, 4 fields. Direct JSON parse.</li>
 *   <li><b>{@code v1:} prefix</b> — phase-2 format. Strip the prefix, parse
 *       the inner base64(JSON), read the 4 MVP fields, <em>ignore</em> the
 *       extra {@code consulAddr} / {@code consulPort} fields. MVP never
 *       produces this format, but MVP decode MUST accept it so the rolling
 *       migration window (phase-2 control plane encoding {@code v1:...}
 *       handles while phase-1 forwarding layer still runs) does not break
 *       silently. See ADR-0160 decision 3 for the rationale.</li>
 * </ul>
 *
 * <p>Malformed handles (bad base64, missing JSON fields, unknown prefix)
 * raise {@link IllegalArgumentException} — the
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
 * <p>Authority: ADR-0160 decision 3 + HD3-006 (opaque route handle).
 */
final class RouteHandleCodec {

    /**
     * Phase-2 prefix marker. MVP encode never produces this prefix; MVP
     * decode MUST recognize it so the rolling-migration window is safe.
     */
    static final String V1_PREFIX = "v1:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RouteHandleCodec() {
        // utility class — no instances
    }

    /**
     * Encode the 4 MVP fields into an opaque handle. Format:
     * {@code base64(JSON{tenantId, agentId, routeKey, contractVersion})}, no
     * prefix.
     *
     * @param tenantId        registry-key tenant dimension (HD3-003)
     * @param agentId         registry-key agent dimension
     * @param routeKey        logical routing key the forwarding layer uses to
     *                        address the agent
     * @param contractVersion contract version the agent pinned at registration
     * @return opaque route handle; never {@code null}
     */
    static String encode(String tenantId, String agentId, String routeKey, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(routeKey, "routeKey");
        requireNonBlank(contractVersion, "contractVersion");
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = MAPPER.writeValueAsBytes(node);
            return Base64.getEncoder().encodeToString(json);
        } catch (Exception ex) {
            // Jackson failures on plain String values are not recoverable —
            // wrap as IllegalArgumentException per SPI contract.
            throw new IllegalArgumentException("failed to encode route handle", ex);
        }
    }

    /**
     * Decode an opaque handle into the 4 MVP fields. Accepts both no-prefix
     * (MVP) and {@code v1:} prefix (phase-2) formats; ignores any extra
     * fields the phase-2 format carries.
     *
     * @param handle opaque handle produced by {@link #encode} or by the
     *               phase-2 encoder
     * @return decoded 4-field view; never {@code null}
     * @throws IllegalArgumentException if the handle is malformed (bad base64,
     *         bad JSON, missing fields, unknown prefix)
     */
    static DecodedHandle decode(String handle) {
        requireNonBlank(handle, "handle");
        String payload = stripPrefix(handle);
        JsonNode node = parseJson(payload);
        return new DecodedHandle(
                requireTextField(node, "tenantId"),
                requireTextField(node, "agentId"),
                requireTextField(node, "routeKey"),
                requireTextField(node, "contractVersion"));
    }

    /**
     * Strip the {@code v1:} prefix if present. MVP handles have no prefix;
     * phase-2 handles carry {@code v1:} before the base64 payload. Any other
     * prefix is rejected as malformed.
     */
    private static String stripPrefix(String handle) {
        if (handle.startsWith(V1_PREFIX)) {
            return handle.substring(V1_PREFIX.length());
        }
        return handle;
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
     * Decoded view of a route handle — the 4 MVP fields. Phase-2 fields
     * ({@code consulAddr} / {@code consulPort}) are intentionally absent;
     * the forwarding layer's {@code resolveRouteHandle} never needs them in
     * MVP (it routes via the repository's {@code findEndpoint} lookup).
     */
    record DecodedHandle(String tenantId, String agentId, String routeKey, String contractVersion) {
    }
}
