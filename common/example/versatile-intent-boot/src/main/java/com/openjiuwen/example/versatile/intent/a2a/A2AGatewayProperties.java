/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the A2A Gateway routing mode.
 *
 * <p>Bound to {@code openjiuwen.service.a2a-gateway.*}. When
 * {@link #isEnabled} is {@code true}, the deployment module injects
 * {@link A2AGatewayRemoteAgentCaller} / {@link A2AGatewayCardResolver} as the
 * active {@code RemoteAgentCaller} / {@code RemoteAgentCardResolver} beans,
 * overriding the runtime core module's {@code Default*} implementations.
 *
 * <p>{@link #callTimeoutSeconds} bounds the wait for each cross-layer A2A
 * call. It must not exceed the per-hop budget remaining from the original
 * chain deadline (PRD §9.3); deployments lower it as needed.
 *
 * <p>{@link #token} is the service-level credential sent as the {@code token}
 * header on every gateway request (required). {@link #versionNode} is an
 * optional routing hint sent as the {@code versionNode} header. Per-request
 * {@code userId} is resolved at call time from
 * {@link com.openjiuwen.service.spec.dto.ServeRequest#getUserId()}; tracing
 * headers ({@code X-B3-TraceId}, {@code X-B3-Sampled}, {@code X-Biz-Tag}) and
 * the parent span id (derived from the upstream {@code X-B3-SpanId}) are
 * propagated from the upstream HTTP request, while a fresh
 * {@code X-B3-SpanId} is generated per call.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.a2a-gateway")
public class A2AGatewayProperties {
    /**
     * Default JSON-RPC path template matching the gateway's
     * {@code {a2a-gateway}/a2a/{agentId}} URL convention.
     */
    public static final String DEFAULT_JSONRPC_PATH = "/a2a/{agentCard}";

    private boolean enabled = false;
    private String baseUrl;
    private String jsonRpcPath = DEFAULT_JSONRPC_PATH;
    private long callTimeoutSeconds = 300L;

    /**
     * Service-level authentication token forwarded as the {@code token} header.
     * Required when {@link #isEnabled} is {@code true}; the caller refuses to
     * send requests without it.
     */
    private String token;

    /**
     * Optional version-node routing hint forwarded as the {@code versionNode}
     * header. Only added when non-blank.
     */
    private String versionNode;

    /**
     * Whether to use A2A streaming ({@code message/stream} SSE) for cross-layer
     * calls. Defaults to {@code true}. Set to {@code false} for gateways that
     * only support sync {@code message/send} (single JSON-RPC response).
     */
    private boolean streaming = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getJsonRpcPath() {
        return jsonRpcPath;
    }

    public void setJsonRpcPath(String jsonRpcPath) {
        this.jsonRpcPath = jsonRpcPath;
    }

    public long getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    public void setCallTimeoutSeconds(long callTimeoutSeconds) {
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getVersionNode() {
        return versionNode;
    }

    public void setVersionNode(String versionNode) {
        this.versionNode = versionNode;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }
}
