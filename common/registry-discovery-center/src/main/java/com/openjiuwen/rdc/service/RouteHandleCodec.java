/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encode / decode opaque route handles (ADR-0160 decision 3, HD3-006).
 *
 * <p>The route handle is the <b>only</b> reference an
 * {@link com.openjiuwen.rdc.service.AgentDiscoveryService} caller
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
 * {@link com.openjiuwen.rdc.model.TenantIsolationViolationException}
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
 *
 * @since 2026-07-10
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
     * @param fields the 6 route-handle fields (tenantId, agentId, serviceId,
     *               instanceId, routeKey, contractVersion); must not be {@code null}
     *
     * @return opaque route handle with {@code v2:} prefix; never {@code null}
     */

    static String encode(HandleFields fields) {
        requireNonBlank(fields.tenantId(), "tenantId");
        requireNonBlank(fields.agentId(), "agentId");
        requireNonBlank(fields.serviceId(), "serviceId");
        requireNonBlank(fields.instanceId(), "instanceId");
        requireNonBlank(fields.routeKey(), "routeKey");
        requireNonBlank(fields.contractVersion(), "contractVersion");
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("tenantId", fields.tenantId());
            node.put("agentId", fields.agentId());
            node.put("serviceId", fields.serviceId());
            node.put("instanceId", fields.instanceId());
            node.put("routeKey", fields.routeKey());
            node.put("contractVersion", fields.contractVersion());
            byte[] json = MAPPER.writeValueAsBytes(node);
            return V2_PREFIX + Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException ex) {
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

    static HandleFields decode(String handle) {
        requireNonBlank(handle, "handle");
        String payload = stripPrefix(handle);
        JsonNode node = parseJson(payload);
        return new HandleFields(
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
     *
     * @param handle opaque handle produced by {@link #encode}
     * @return the base64 payload after the {@code v2:} prefix
     * @throws IllegalArgumentException if the handle lacks the {@code v2:} prefix
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
        } catch (JsonProcessingException ex) {
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
     * The 6 FEAT-016 route-handle fields. Used both as encode input and as the
     * decoded view returned by {@link #decode}. The {@code serviceId} field is
     * the logical service identifier; the {@code instanceId} field lets the
     * forwarding layer resolve a specific instance among N replicas.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @param routeKey routeKey
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */

    record HandleFields(
            String tenantId, String agentId, String serviceId,
            String instanceId, String routeKey, String contractVersion) {
        }
    }