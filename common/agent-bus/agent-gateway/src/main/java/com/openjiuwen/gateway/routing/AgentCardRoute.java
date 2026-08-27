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
 * {@code AgentCardDto}. v0830: carries {@code weight} for weighted load-balancing
 * across multiple instances of the same Agent (supplement info 1).
 *
 * @param routeHandle    opaque route reference (forward layer resolves it)
 * @param targetServiceId runtime serviceId (BUS envelope target)
 * @param weight         load weight for weighted selection (≥1; default 1)
 * @since 0.1.0
 */
public record AgentCardRoute(String routeHandle, String targetServiceId, int weight) {

    /**
     * Backward-compat constructor for DIRECT (011) — targetServiceId/weight not needed.
     *
     * @param routeHandle opaque route reference
     */
    public AgentCardRoute(String routeHandle) {
        this(routeHandle, null, 1);
    }

    /**
     * Two-arg constructor (targetServiceId set, weight defaults to 1).
     *
     * @param routeHandle    opaque route reference
     * @param targetServiceId runtime serviceId
     */
    public AgentCardRoute(String routeHandle, String targetServiceId) {
        this(routeHandle, targetServiceId, 1);
    }
}
