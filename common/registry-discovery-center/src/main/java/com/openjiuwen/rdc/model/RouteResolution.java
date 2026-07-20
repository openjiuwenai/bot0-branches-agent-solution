/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Resolved view of an opaque {@code routeHandle} — returned by
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)} to the
 * forwarding delivery infrastructure only.
 *
 * <p>FEAT-016 adds {@code instanceId} (decoded from the v2: 6-field handle).
 * Feat-015 adds {@code capabilityVersion} for Card-level routing metadata.
 *
 * @since 0.1.0
 */
public record RouteResolution(
        String instanceId,
        String endpointUrl,
        String routeKey,
        String contractVersion,
        String capabilityVersion) {
}
