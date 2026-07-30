/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

/**
 * Stable governance / routing error body returned to the caller at the HTTP
 * layer (FEAT-011 L2 §3 — error body minimal set: {@code code}, {@code message};
 * optional {@code traceId}). This is NOT a JSON-RPC error envelope:
 * governance / validation / routing failures surface as HTTP status + this body,
 * and the caller must treat them as failures, never as an accepted Task
 * (L2 §4.9 AC-7).
 *
 * <p>The body never contains topology — no endpoint, routeHandle, or instance
 * address (L2 §0.2 OUT-8).
 *
 * @param code stable error code (AUTH_*, TENANT_*, VALIDATION_*, IDEMPOTENCY_*, ROUTE_*)
 * @param message human-readable message (safe to show callers)
 * @param traceId optional correlation id
 * @since 0.1.0
 */
public record GatewayError(String code, String message, String traceId) {
}
