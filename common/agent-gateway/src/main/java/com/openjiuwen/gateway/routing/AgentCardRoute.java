/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

/**
 * Gateway's view of one routable instance returned by RDC. Carries only the
 * opaque {@code routeHandle} the gateway needs to resolve; the physical
 * endpoint is never exposed here (recovered only via
 * {@link RdcRouteClient#resolveRouteHandle} in the forward layer).
 *
 * <p>This is the gateway's own decoupled DTO over the wire from RDC's
 * {@code AgentCardDto}; it intentionally carries nothing the gateway does not
 * need (no health/weight, since the gateway takes RDC's sorted first and does
 * not re-filter — FEAT-011 L2 §4.4 P1, decision D3).
 *
 * @param routeHandle opaque route reference (forward layer resolves it)
 * @since 0.1.0
 */
public record AgentCardRoute(String routeHandle) {
}
