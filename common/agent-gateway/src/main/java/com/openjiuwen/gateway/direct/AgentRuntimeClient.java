/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

/**
 * Gateway's outbound port to agent-runtime's standard A2A entry
 * ({@code POST /a2a} — FEAT-011 L2 §4.5.3 / §4.10 edge I-03, FEAT-001). The 730
 * default implementation forwards over HTTP/SSE; tests stub this port. The
 * gateway forwards the A2A body opaquely — no business-semantic parsing
 * (FEAT-011 L2 §0.2 OUT-7, §4.10 GW-RT-8).
 *
 * <p>Streaming support ({@code SendStreamingMessage} / SSE bridging) is added
 * in the S2-SSE slice; this interface currently declares the synchronous path
 * only and grows as later slices require.
 *
 * @since 0.1.0
 */
public interface AgentRuntimeClient {
    /**
     * Forward a synchronous {@code SendMessage} to the resolved runtime endpoint
     * and return the runtime's JSON-RPC response body (forwarded opaquely).
     *
     * @param endpointUrl resolved runtime endpoint (base of {@code POST /a2a})
     * @param jsonRpcBody A2A JSON-RPC envelope (with authoritative tenant injected)
     * @return runtime's JSON-RPC response body
     */
    String invokeSync(String endpointUrl, String jsonRpcBody);
}
