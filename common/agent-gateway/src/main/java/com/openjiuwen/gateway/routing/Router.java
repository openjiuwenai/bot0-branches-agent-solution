/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.gateway.direct.AgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Routes a create call over the direct path (FEAT-011 L2 §4): resolve the
 * effective agent (explicit or default) → search RDC → pick the first candidate
 * → resolve the route handle → inject the authoritative tenant → forward
 * synchronously → on first taskId, bind the sticky index. Failures (no
 * candidate / resolve failure) surface as governance-layer errors that the S5
 * path returns — never a fabricated success.
 *
 * @since 0.1.0
 */
@Component
public class Router {
    private final RdcRouteClient rdc;
    private final AgentRuntimeClient runtime;
    private final StickyIndex stickyIndex;
    private final DefaultAgentResolver defaultAgentResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Construct.
     *
     * @param rdc                  RDC route client
     * @param runtime              runtime forwarder
     * @param stickyIndex          taskId -> routeHandle index
     * @param defaultAgentResolver default agent resolver
     */
    public Router(RdcRouteClient rdc, AgentRuntimeClient runtime, StickyIndex stickyIndex,
                  DefaultAgentResolver defaultAgentResolver) {
        this.rdc = rdc;
        this.runtime = runtime;
        this.stickyIndex = stickyIndex;
        this.defaultAgentResolver = defaultAgentResolver;
    }

    /**
     * Route a create request synchronously and return the runtime's JSON-RPC body.
     *
     * @param ctx governance context (tenantId, agentId, rawBody)
     * @return the runtime response body (forwarded without adding routeHandle/endpoint;
     *         the runtime (FEAT-001) is responsible for not returning physical topology)
     */
    public String routeCreate(GovernanceContext ctx) {
        String effectiveAgentId = ctx.agentId() != null ? ctx.agentId() : defaultAgentResolver.resolve();
        List<AgentCardRoute> candidates = rdc.searchInstancesByAgentId(ctx.tenantId(), effectiveAgentId);
        if (candidates.isEmpty()) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NO_CANDIDATES",
                    "No routable instance for agent " + effectiveAgentId);
        }
        AgentCardRoute chosen = candidates.get(0);
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(chosen.routeHandle(), ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        String response = runtime.invokeSync(resolved.endpointUrl(), outbound);
        String taskId = extractTaskId(response);
        if (taskId != null && !taskId.isBlank()) {
            stickyIndex.put(taskId, chosen.routeHandle());
        }
        return response;
    }

    /**
     * Route a streaming create and return the runtime's frame stream (L2 §4 P3b).
     * Routing (search/resolve/inject) happens synchronously here so failures
     * surface as clean errors; frame consumption is lazy. The first frame carrying
     * a taskId binds the sticky index. Closing the returned stream releases the
     * downstream connection.
     *
     * @param ctx governance context (tenantId, agentId, rawBody)
     * @return lazy stream of SSE data payloads (sticky-write hooked)
     */
    public Stream<String> routeStream(GovernanceContext ctx) {
        String effectiveAgentId = ctx.agentId() != null ? ctx.agentId() : defaultAgentResolver.resolve();
        List<AgentCardRoute> candidates = rdc.searchInstancesByAgentId(ctx.tenantId(), effectiveAgentId);
        if (candidates.isEmpty()) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NO_CANDIDATES",
                    "No routable instance for agent " + effectiveAgentId);
        }
        AgentCardRoute chosen = candidates.get(0);
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(chosen.routeHandle(), ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        Stream<String> frames = runtime.openStream(resolved.endpointUrl(), outbound);
        AtomicBoolean stickyWritten = new AtomicBoolean();
        return frames.peek(frame -> {
            if (!stickyWritten.get()) {
                String taskId = extractTaskId(frame);
                if (taskId != null && !taskId.isBlank() && stickyWritten.compareAndSet(false, true)) {
                    stickyIndex.put(taskId, chosen.routeHandle());
                }
            }
        });
    }

    /**
     * Route a resume to its original Task owner via the sticky index (L2 §5).
     * Read-only: does NOT call {@code searchInstancesByAgentId} (no re-selection).
     * A sticky miss is a definite failure (never a new create / fallback search).
     * Runtime association errors (-32001/-32004) are passed through in the body.
     *
     * @param ctx governance context (tenantId, taskId, rawBody)
     * @return the runtime response body (passed through as-is)
     */
    public String routeResume(GovernanceContext ctx) {
        String taskId = ctx.taskId();
        String routeHandle = stickyIndex.find(taskId)
                .orElseThrow(() -> new GovernanceException(HttpStatus.NOT_FOUND, "RESUME_OWNER_UNKNOWN",
                        "No sticky owner for task " + taskId));
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(routeHandle, ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        return runtime.invokeSync(resolved.endpointUrl(), outbound);
    }

    /**
     * Inject the authoritative tenant into {@code params.metadata.tenantId} (AC-RT-1 / GW-RT-10).
     */
    String injectTenantId(String rawBody, String tenantId) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            if (root.isObject()) {
                ObjectNode params = withObject(root, "params");
                ObjectNode metadata = withObject(params, "metadata");
                metadata.put("tenantId", tenantId);
                return mapper.writeValueAsString(root);
            }
            return rawBody;
        } catch (Exception ex) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "Cannot inject tenant into body");
        }
    }

    /**
     * Extract the task id from a runtime response / SSE frame.
     * Accepts A2A shapes used in the wild: {@code result.id}, {@code result.taskId},
     * {@code result.task.id}, and nested {@code result.statusUpdate.taskId}
     * (FEAT-001 / status-update frames). Missing any of these left sticky unbound
     * so tool/user-input resume failed with {@code RESUME_OWNER_UNKNOWN}.
     */
    String extractTaskId(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode result = root.path("result");
            String id = text(result, "id");
            if (id == null || id.isBlank()) {
                id = text(result, "taskId");
            }
            if (id == null || id.isBlank()) {
                id = text(result.path("task"), "id");
            }
            if (id == null || id.isBlank()) {
                id = text(result.path("statusUpdate"), "taskId");
            }
            return id;
        } catch (Exception ex) {
            return null;
        }
    }

    private static ObjectNode withObject(JsonNode parent, String field) {
        JsonNode child = parent.path(field);
        if (child instanceof ObjectNode objectChild) {
            return objectChild;
        }
        if (parent instanceof ObjectNode objectParent) {
            return objectParent.putObject(field);
        }
        throw new ClassCastException("parent JsonNode is not an ObjectNode: " + parent.getNodeType());
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return (node.isMissingNode() || node.isNull()) ? null : node.asText();
    }
}
