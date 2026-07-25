/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

/**
 * Forward-layer-only resolution of an opaque {@code routeHandle} to the target
 * runtime's physical endpoint. Never returned to the client / agent
 * (FEAT-011 L2 §4.5.2, FEAT-016 §2.3.2 RouteResolution).
 *
 * @param endpointUrl runtime standard A2A endpoint (base of {@code POST /a2a})
 * @since 0.1.0
 */
public record ResolvedRoute(String endpointUrl) {
}
