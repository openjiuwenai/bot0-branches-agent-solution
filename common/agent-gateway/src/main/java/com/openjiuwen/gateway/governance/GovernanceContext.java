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

    /** @return trace correlation id (from traceparent or self-generated) */
    public String traceId() {
        return traceId;
    }

    /** @param traceId correlation id */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /** @return authenticated principal id (G1) */
    public String principalId() {
        return principalId;
    }

    /** @param principalId authenticated principal id */
    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    /** @return authoritative tenant id (G2) */
    public String tenantId() {
        return tenantId;
    }

    /** @param tenantId authoritative tenant id */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /** @return JSON-RPC method (G3) */
    public String method() {
        return method;
    }

    /** @param method JSON-RPC method */
    public void setMethod(String method) {
        this.method = method;
    }

    /** @return effective logical agent id (G3 / routing) */
    public String agentId() {
        return agentId;
    }

    /** @param agentId logical agent id */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /** @return resume task id (G3) */
    public String taskId() {
        return taskId;
    }

    /** @param taskId resume task id */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /** @return create idempotency key / messageId (G4) */
    public String messageId() {
        return messageId;
    }

    /** @param messageId create idempotency key */
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /** @return conversation context id */
    public String contextId() {
        return contextId;
    }

    /** @param contextId conversation context id */
    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    /** @return raw JSON-RPC body (parsed lazily by G3) */
    public String rawBody() {
        return rawBody;
    }

    /** @param rawBody raw JSON-RPC body */
    public void setRawBody(String rawBody) {
        this.rawBody = rawBody;
    }
}
