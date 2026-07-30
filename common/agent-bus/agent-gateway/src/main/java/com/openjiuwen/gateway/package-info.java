/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-gateway — client-facing A2A governance + direct route-forwarding entry
 * (FEAT-011). The gateway receives A2A JSON-RPC calls ({@code POST /a2a}),
 * applies ingress governance (auth / tenant / validate / idempotency / audit),
 * routes by logical target via the registry-discovery-center, and forwards
 * synchronously or via SSE bridging to the target agent-runtime — without
 * executing agents, owning Task state, or exposing topology.
 *
 * <p>Package layout follows L2 §1.3:
 * <ul>
 *   <li>{@code facade} — JSON-RPC parse, method dispatch, response / SSE write</li>
 *   <li>{@code governance} — G1 auth, G2 tenant, G3 validate, G4 idempotency, G5 audit</li>
 *   <li>{@code routing} — default Agent resolution, RDC port + HTTP impl, pick-first, sticky index</li>
 *   <li>{@code direct} — runtime port + HTTP sync forward</li>
 *   <li>{@code sse} — SSE bridging + release</li>
 *   <li>{@code path} — DIRECT vs BUS (730 fixed DIRECT)</li>
 *   <li>{@code obs} — audit sink, route trace, traceId</li>
 * </ul>
 *
 * <p>Downstream RDC ({@link com.openjiuwen.gateway.routing.RdcRouteClient}) and
 * runtime ({@link com.openjiuwen.gateway.direct.AgentRuntimeClient}) are
 * hexagonal ports with HTTP default implementations — never in-process
 * dependencies — so the gateway is independently deployable and testable with
 * stubs (L2 §1.6, decision D2/D6).
 */
package com.openjiuwen.gateway;
