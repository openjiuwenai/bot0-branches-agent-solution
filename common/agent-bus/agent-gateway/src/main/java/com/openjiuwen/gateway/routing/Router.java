/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.gateway.direct.AgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.ErrorCodes;
import com.openjiuwen.gateway.governance.MethodResultException;
import com.openjiuwen.gateway.governance.GovernanceContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
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
        AgentCardRoute chosen = selectByWeight(candidates);
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(chosen.routeHandle(), ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        String response = runtime.invokeSync(resolved.endpointUrl(), outbound);
        extractTaskId(response).filter(s -> !s.isBlank()).ifPresent(
                taskId -> stickyIndex.put(taskId, chosen.routeHandle(), chosen.targetServiceId()));
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
        AgentCardRoute chosen = selectByWeight(candidates);
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
                extractTaskId(frame)
                        .filter(s -> !s.isBlank())
                        .ifPresent(taskId -> {
                            if (stickyWritten.compareAndSet(false, true)) {
                                stickyIndex.put(taskId, chosen.routeHandle(), chosen.targetServiceId());
                            }
                        });
            }
        });
    }

    /**
     * Route a streaming resume to its original Task owner via the sticky index.
     * Read-only: does NOT call {@code searchInstancesByAgentId} (no re-selection).
     * A sticky miss is a definite failure (never a new create / fallback search).
     *
     * <p>与 {@link #routeStream}（流式创建）同构，但路由依据是 sticky index 中已有的
     * taskId → routeHandle 绑定（创建时写入），不做 agent 搜索。用于首轮 STREAMING
     * 的续跑场景（FEAT-006 §47：续轮继承首轮 mode，STREAMING 续跑走 SendStreamingMessage）。
     *
     * @param ctx governance context (tenantId, taskId, rawBody)
     * @return lazy stream of SSE data payloads (sticky-write hooked, idempotent)
     */
    public Stream<String> routeResumeStream(GovernanceContext ctx) {
        String taskId = ctx.taskId();
        String routeHandle = stickyIndex.find(taskId)
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + taskId, null));
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(routeHandle, ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        return runtime.openStream(resolved.endpointUrl(), outbound);
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
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + taskId, null));
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
     * Route a GetTask query to its Task owner via the sticky index (v0830 S6,
     * FEAT-011 L2 §8.1). Read-only: no Task creation, no Agent execution.
     * Sticky miss → CONTINUATION_FAILED (task owner not found).
     * Runtime -32001 (task not found) passed through in the body.
     *
     * @param ctx governance context (tenantId, taskId)
     * @return the runtime response body (Task snapshot or JSON-RPC error -32001)
     */
    public String routeGet(GovernanceContext ctx) {
        String taskId = ctx.taskId();
        String routeHandle = stickyIndex.find(taskId)
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + taskId, null));
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(routeHandle, ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        return runtime.getTask(resolved.endpointUrl(), taskId, ctx.tenantId(), ctx.historyLength());
    }

    /**
     * Route a SubscribeToTask re-subscription to the Task owner via the sticky index
     * (v0830 S8 direct, FEAT-011 L2 §8.3). Sticky miss → CONTINUATION_FAILED.
     * Opens SSE to runtime's standard SubscribeToTask entry (no streamRef for direct path).
     *
     * @param ctx governance context (tenantId, taskId, rawBody)
     * @return lazy stream of SSE data payloads (runtime SubscribeToTask SSE)
     */
    public Stream<String> routeSubscribe(GovernanceContext ctx) {
        String taskId = ctx.taskId();
        String routeHandle = stickyIndex.find(taskId)
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + taskId, null));
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(routeHandle, ctx.tenantId());
        } catch (RouteResolutionException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_RESOLVE_FAILED",
                    "Cannot resolve route handle", ex);
        }
        String outbound = injectTenantId(ctx.rawBody(), ctx.tenantId());
        return runtime.openStream(resolved.endpointUrl(), outbound);
    }

    /**
     * Inject the authoritative tenant into {@code params.tenant} (AC-RT-1 / GW-RT-10,
     * v0830 — runtime expects {@code params.tenant}, NOT {@code params.metadata.tenantId}).
     *
     * @param rawBody  original JSON-RPC body
     * @param tenantId authoritative tenant from G2
     * @return body with {@code params.tenant} set, or the original body if not an object
     */
    String injectTenantId(String rawBody, String tenantId) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            if (root.isObject()) {
                ObjectNode params = withObject(root, "params");
                params.put("tenant", tenantId);
                return mapper.writeValueAsString(root);
            }
            return rawBody;
        } catch (JsonProcessingException ex) {
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
     *
     * @param response runtime JSON-RPC body or SSE frame
     * @return the extracted task id, or empty if not found / unparseable
     */
    Optional<String> extractTaskId(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode result = root.path("result");
            return text(result, "id")
                    .filter(s -> !s.isBlank())
                    .or(() -> text(result, "taskId").filter(s -> !s.isBlank()))
                    .or(() -> text(result.path("task"), "id").filter(s -> !s.isBlank()))
                    .or(() -> text(result.path("statusUpdate"), "taskId").filter(s -> !s.isBlank()));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
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

    private static Optional<String> text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return (node.isMissingNode() || node.isNull()) ? Optional.empty() : Optional.of(node.asText());
    }

    /**
     * Weighted load-balancing selection from RDC candidates (supplement info 1,
     * v0830). Picks one instance with probability proportional to its weight,
     * not always the first. Weight ≤0 is treated as 1 (excluded is RDC's job).
     *
     * @param candidates non-empty list from RDC (caller checks empty)
     * @return the selected instance
     */
    public static AgentCardRoute selectByWeight(List<AgentCardRoute> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        int total = 0;
        for (AgentCardRoute c : candidates) {
            total += Math.max(c.weight(), 1);
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (AgentCardRoute c : candidates) {
            acc += Math.max(c.weight(), 1);
            if (pick < acc) {
                return c;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
