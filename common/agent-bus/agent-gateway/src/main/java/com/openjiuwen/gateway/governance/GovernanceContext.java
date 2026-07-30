/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

/**
 * Mutable trusted context accumulated as the governance pipeline (G1→G5)
 * runs for one inbound request (FEAT-011 L2 §3). Each step either populates a
 * field or throws {@link GovernanceException}. Downstream routing / forwarding
 * read the authoritative values from here (e.g. {@code tenantId} is the value
 * G2 resolved, never the caller's self-report).
 *
 * <p>Fields are added per slice as later G steps need them; only the entries a
 * given slice populates are non-{@code null}.
 *
 * @since 0.1.0
 */
public class GovernanceContext {
    private String traceId;
    private String principalId;
    private String tenantId;
    private String method;
    private String agentId;
    private String taskId;
    private String messageId;
    private String contextId;
    private String rawBody;

    /**
     * Get trace correlation id.
     *
     * @return trace correlation id (from traceparent or self-generated)
     */
    public String traceId() {
        return traceId;
    }

    /**
     * Set trace correlation id.
     *
     * @param traceId correlation id
     */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * Get authenticated principal id.
     *
     * @return authenticated principal id (G1)
     */
    public String principalId() {
        return principalId;
    }

    /**
     * Set authenticated principal id.
     *
     * @param principalId authenticated principal id
     */
    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    /**
     * Get authoritative tenant id.
     *
     * @return authoritative tenant id (G2)
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * Set authoritative tenant id.
     *
     * @param tenantId authoritative tenant id
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Get JSON-RPC method.
     *
     * @return JSON-RPC method (G3)
     */
    public String method() {
        return method;
    }

    /**
     * Set JSON-RPC method.
     *
     * @param method JSON-RPC method
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * Get effective logical agent id.
     *
     * @return effective logical agent id (G3 / routing)
     */
    public String agentId() {
        return agentId;
    }

    /**
     * Set logical agent id.
     *
     * @param agentId logical agent id
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * Get resume task id.
     *
     * @return resume task id (G3)
     */
    public String taskId() {
        return taskId;
    }

    /**
     * Set resume task id.
     *
     * @param taskId resume task id
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Get create idempotency key / messageId.
     *
     * @return create idempotency key / messageId (G4)
     */
    public String messageId() {
        return messageId;
    }

    /**
     * Set create idempotency key.
     *
     * @param messageId create idempotency key
     */
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /**
     * Get conversation context id.
     *
     * @return conversation context id
     */
    public String contextId() {
        return contextId;
    }

    /**
     * Set conversation context id.
     *
     * @param contextId conversation context id
     */
    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    /**
     * Get raw JSON-RPC body.
     *
     * @return raw JSON-RPC body (parsed lazily by G3)
     */
    public String rawBody() {
        return rawBody;
    }

    /**
     * Set raw JSON-RPC body.
     *
     * @param rawBody raw JSON-RPC body
     */
    public void setRawBody(String rawBody) {
        this.rawBody = rawBody;
    }
}
