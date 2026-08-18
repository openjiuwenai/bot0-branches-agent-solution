/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.ProjectionFeed;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;
import com.openjiuwen.gateway.bus.wait.G4BusWiring;
import com.openjiuwen.gateway.bus.wait.WaitWindow;
import com.openjiuwen.gateway.direct.AgentRuntimeClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.gateway.governance.ErrorCodes;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.JsonRpcError;
import com.openjiuwen.gateway.governance.MethodResultException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.DefaultAgentResolver;
import com.openjiuwen.gateway.routing.RdcRouteClient;
import com.openjiuwen.gateway.routing.ResolvedRoute;
import com.openjiuwen.gateway.routing.RouteResolutionException;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.routing.StickyIndex;
import com.openjiuwen.gateway.sse.SseBridge;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Orchestrates the BUS sync create path (FEAT-012 §4): search→enqueue→wait→fold→respond.
 *
 * @since 2026-07-24
 */
public class BusForwarder {
    private static final Logger log = LoggerFactory.getLogger(BusForwarder.class);

    /** Shared mapper for JSON-RPC error serialization (A1). */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final RdcRouteClient rdc;
    private final BusControlForwarder control;
    private final ProjectionFeed projectionFeed;
    private final IdempotencyRule g4;
    private final String sourceServiceId;
    private final long acceptWindowMillis;
    private final long responseWindowMillis;
    private final AgentRuntimeClient agentRuntimeClient;
    private final DefaultAgentResolver defaultAgentResolver;
    private final StickyIndex stickyIndex;
    private long streamFirstFrameDeadlineMillis = 10_000L;
    private long singleResponseWindowMillis = 30_000L;

    /** Dedicated daemon threads for bounded first-frame reads (never blocks a caller thread). */
    private final ExecutorService firstFrameExec = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
            TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "bus-forwarder-firstframe");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        log.warn("first-frame reader uncaught", ex));
                return t;
            });

    /**
     * Creates a forwarder wired with RDC search, control enqueue, projection feed, G4,
     * SSE-bridge runtime client, and the default-agent fallback.
     *
     * <p>When a create carries no {@code agentId}, the {@link DefaultAgentResolver} supplies
     * the configured default (mirrors {@code Router}); a missing default is a clean
     * {@code DEFAULT_AGENT_UNCONFIGURED} governance error rather than an NPE.
     *
     * @param rdc route discovery client
     * @param control I-04 outbound forwarder
     * @param projectionFeed inbound projection poll port
     * @param g4 idempotency rule (G4)
     * @param sourceServiceId gateway service identity for envelope audit
     * @param acceptWindowMillis accept-phase timeout window
     * @param responseWindowMillis response-phase timeout window after accept
     * @param agentRuntimeClient runtime client for SSE bridge after STREAM_READY (null on sync-only wiring)
     * @param defaultAgentResolver default logical agent resolver (used when ctx carries no agentId)
     * @param stickyIndex taskId -> routeHandle index; written on the first taskId-bearing projection
     *                    so a BUS-created task resumes to its owning runtime (P-13, mirrors DIRECT
     *                    Router.routeCreate which writes sticky from the response taskId)
     */
    public BusForwarder(RdcRouteClient rdc, BusControlForwarder control, ProjectionFeed projectionFeed,
                        IdempotencyRule g4, String sourceServiceId,
                        long acceptWindowMillis, long responseWindowMillis,
                        AgentRuntimeClient agentRuntimeClient, DefaultAgentResolver defaultAgentResolver,
                        StickyIndex stickyIndex) {
        this.rdc = rdc;
        this.control = control;
        this.projectionFeed = projectionFeed;
        this.g4 = g4;
        this.sourceServiceId = sourceServiceId;
        this.acceptWindowMillis = acceptWindowMillis;
        this.responseWindowMillis = responseWindowMillis;
        this.agentRuntimeClient = agentRuntimeClient;
        this.defaultAgentResolver = defaultAgentResolver;
        this.stickyIndex = stickyIndex;
    }

    /**
     * Sets the deadline to read the first SSE frame after STREAM_READY (FEAT-012 IN-4 robustness).
     * A runtime that accepts SubscribeToTask but never sends a frame (e.g. an already-terminal
     * task whose subscription does not close) must not hang the servlet thread forever; the
     * forwarder aborts with {@code STREAM_DEADLINE_EXCEEDED} once the deadline elapses.
     *
     * @param streamFirstFrameDeadlineMillis deadline in milliseconds (default 10s)
     */
    public void setStreamFirstFrameDeadlineMillis(long streamFirstFrameDeadlineMillis) {
        this.streamFirstFrameDeadlineMillis = streamFirstFrameDeadlineMillis;
    }

    /**
     * Sets the single-response window timeout for QUERY/SUBSCRIBE (FEAT-012 §8.1
     * B-C1). Unlike the create dual-window (accept+response), query/subscribe use a
     * single one-shot window; timeout → definite failure (not UNKNOWN).
     *
     * @param singleResponseWindowMillis timeout in milliseconds (default 30000)
     */
    public void setSingleResponseWindowMillis(long singleResponseWindowMillis) {
        this.singleResponseWindowMillis = singleResponseWindowMillis;
    }

    /**
     * Runs the BUS query path for GetTask (v0830 S6). Uses single-response window (not
     * accept/response dual), CLIENT_INVOCATION_QUERY_REQUESTED event, no G4 admission.
     * Polls for INVOCATION_RESPONSE / INVOCATION_FAILED; resolves payloadRef if present.
     * Timeout → CONTINUATION_FAILED (definite failure, not UNKNOWN).
     *
     * @param ctx governance context (tenantId, taskId, rawBody)
     * @return HTTP 200 with Task snapshot body or JSON-RPC error
     * @throws GovernanceException when no routable instance or enqueue fails
     */
    public ResponseEntity<String> forwardQuery(GovernanceContext ctx) {
        // S6 (BUS): route the query to the task's STICKY OWNER (routeHandle + targetServiceId bound at
        // create), not a default-agent runtime picked from RDC. A GetTask body carries only params.id
        // (no agentId), so without the sticky binding the gateway cannot know which runtime owns the
        // task — a default-agent fallback would query the wrong runtime (spurious TASK_NOT_FOUND) and
        // diverge from DIRECT routeGet. A sticky miss → CONTINUATION_FAILED (definite, not UNKNOWN).
        StickyIndex.Owner owner = stickyIndex.findOwner(ctx.taskId())
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + ctx.taskId(), null));
        ForwardingEnvelope env = control.forwardQuery(ctx, owner.routeHandle(), owner.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardQuery start corrId={} tenant={} taskId={} target={}",
                correlationId, ctx.tenantId(), ctx.taskId(), owner.targetServiceId());
        long start = System.currentTimeMillis();
        int maxPolls = 100;
        for (int i = 0; i < maxPolls; i++) {
            if (System.currentTimeMillis() - start > singleResponseWindowMillis) {
                log.info("forwardQuery corrId={} TIMEOUT (single-response-window {}ms)",
                        correlationId, singleResponseWindowMillis);
                return ResponseEntity.ok().body(
                        statusBody(InvocationResponseStatus.FAILED, null, "Query timeout"));
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isEmpty()) {
                continue;
            }
            var event = proj.get();
            InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
            if (folded == InvocationResponseStatus.COMPLETED_RESPONSE) {
                String body = event.body();
                if (body == null && event.payloadRef() != null) {
                    body = "payloadRef:" + event.payloadRef();
                }
                log.info("forwardQuery corrId={} RESPONSE taskId={}", correlationId, event.taskId());
                String responseBody = statusBody(folded, event.taskId(), body);
                return ResponseEntity.ok().body(responseBody);
            }
            if (FiveStateFolder.isTerminal(folded) || folded == InvocationResponseStatus.REJECTED
                    || folded == InvocationResponseStatus.FAILED) {
                String responseBody = statusBody(folded, event.taskId(), event.body());
                log.info("forwardQuery corrId={} folded={} bodyPresent={}",
                        correlationId, folded, event.body() != null);
                return ResponseEntity.ok().body(responseBody);
            }
        }
        return ResponseEntity.ok().body(
                statusBody(InvocationResponseStatus.FAILED, null, "Query exhausted poll budget"));
    }

    /**
     * Runs the BUS subscribe path for SubscribeToTask (v0830 S8). Publishes
     * CLIENT_STREAM_SUBSCRIBE_REQUESTED, polls for STREAM_READY (carrying streamRef),
     * then bridges I-06 SSE via openStreamByRef. Timeout → STREAM_NOT_AVAILABLE.
     *
     * @param ctx governance context (tenantId, taskId, rawBody)
     * @param response servlet response for SSE output
     * @param sseBridge SSE bridge
     * @return empty if SSE written; non-empty error body if STREAM_READY not reached
     */
    public Optional<String> forwardSubscribe(GovernanceContext ctx, HttpServletResponse response,
                                             SseBridge sseBridge) {
        // S8 (BUS): route the subscription to the task's STICKY OWNER (routeHandle + targetServiceId
        // bound at create), not a default-agent runtime picked from RDC. SubscribeToTask carries only
        // params.id (no agentId), so without the sticky binding the gateway cannot know which runtime
        // owns the task — a default-agent fallback would query the wrong runtime (no STREAM_READY →
        // STREAM_NOT_AVAILABLE) and diverge from DIRECT routeSubscribe. A sticky miss →
        // CONTINUATION_FAILED (definite, not a STREAM_NOT_AVAILABLE timeout).
        StickyIndex.Owner owner = stickyIndex.findOwner(ctx.taskId())
                .orElseThrow(() -> new MethodResultException(ErrorCodes.CONTINUATION_FAILED,
                        "no sticky owner for task " + ctx.taskId(), null));
        ForwardingEnvelope env = control.forwardSubscribe(ctx, owner.routeHandle(), owner.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardSubscribe start corrId={} tenant={} taskId={} target={}",
                correlationId, ctx.tenantId(), ctx.taskId(), owner.targetServiceId());

        long start = System.currentTimeMillis();
        int maxPolls = 100;
        for (int i = 0; i < maxPolls; i++) {
            if (System.currentTimeMillis() - start > singleResponseWindowMillis) {
                log.info("forwardSubscribe corrId={} TIMEOUT→STREAM_NOT_AVAILABLE", correlationId);
                return Optional.of(statusBody(InvocationResponseStatus.FAILED, null,
                        "STREAM_NOT_AVAILABLE"));
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isEmpty()) {
                continue;
            }
            var event = proj.get();
            if (event.eventType() == AgentBusEventType.INVOCATION_STREAM_READY) {
                var sctx = new SubscribeBridgeCtx(ctx, response, sseBridge, owner, correlationId);
                return bridgeSubscribeStream(sctx, event);
            }
            InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
            if (FiveStateFolder.isTerminal(folded) || folded == InvocationResponseStatus.REJECTED
                    || folded == InvocationResponseStatus.FAILED) {
                return Optional.of(statusBody(folded, event.taskId(), event.body()));
            }
        }
        return Optional.of(statusBody(InvocationResponseStatus.FAILED, null,
                "Subscribe exhausted poll budget"));
    }

    /**
     * Bridges the runtime SSE stream to the client once STREAM_READY is observed.
     *
     * @param sctx subscribe bridge context (governance, response, SSE bridge, owner, correlation id)
     * @param event STREAM_READY projection (carries streamRef/taskId)
     * @return empty if SSE written; a non-empty FAILED body if the stream cannot be opened
     */
    private Optional<String> bridgeSubscribeStream(SubscribeBridgeCtx sctx, ProjectionFeed.ProjectionEvent event) {
        GovernanceContext ctx = sctx.ctx();
        HttpServletResponse response = sctx.response();
        SseBridge sseBridge = sctx.sseBridge();
        StickyIndex.Owner owner = sctx.owner();
        String correlationId = sctx.correlationId();
        String streamRef = event.streamRef();
        String taskId = event.taskId() != null ? event.taskId() : ctx.taskId();
        log.info("forwardSubscribe corrId={} STREAM_READY taskId={} streamRef present={}",
                correlationId, taskId, streamRef != null);
        if (streamRef == null || streamRef.isBlank()) {
            return Optional.of(statusBody(InvocationResponseStatus.FAILED, taskId,
                    "STREAM_NOT_AVAILABLE"));
        }
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(owner.routeHandle(), ctx.tenantId());
        } catch (RouteResolutionException ex) {
            return Optional.of(statusBody(InvocationResponseStatus.FAILED, taskId,
                    "Cannot resolve route for SSE bridge"));
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            OutputStream out = response.getOutputStream();
            Stream<String> frames = agentRuntimeClient.openStreamByRef(
                    resolved.endpointUrl(), streamRef, taskId, ctx.tenantId());
            sseBridge.writeSse(out, frames);
            return Optional.empty();
        } catch (IOException ex) {
            log.info("forwardSubscribe corrId={} SSE stream closed after disconnect", correlationId);
            return Optional.empty();
        }
    }

    /**
     * Runs the BUS sync create path: search RDC, enqueue, poll projections, fold to five states.
     *
     * @param ctx governance context (tenant, agent, message, trace, body)
     * @return HTTP 200 with folded status JSON body
     * @throws GovernanceException when no routable instance or enqueue fails
     */
    public ResponseEntity<String> forwardSync(GovernanceContext ctx) {
        String effectiveAgentId = ctx.agentId() != null ? ctx.agentId() : defaultAgentResolver.resolve();
        List<AgentCardRoute> candidates = rdc.searchInstancesByAgentId(ctx.tenantId(), effectiveAgentId);
        if (candidates.isEmpty()) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NO_CANDIDATES",
                    "No routable instance for agent " + effectiveAgentId);
        }
        AgentCardRoute chosen = Router.selectByWeight(candidates);

        ForwardingEnvelope env = control.forward(ctx, chosen.routeHandle(), chosen.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardSync start corrId={} tenant={} target={}",
                correlationId, ctx.tenantId(), chosen.targetServiceId());

        long now = System.currentTimeMillis();
        WaitWindow window = new WaitWindow(now, acceptWindowMillis, responseWindowMillis);
        G4BusWiring g4w = new G4BusWiring(g4);

        // maxPolls must outlast the dual accept+response window so checkTimeout fires (acceptWindowMillis
        // + responseWindowMillis, e.g. 30s + 60s = 90s). 100 polls × 500ms = 50s < 90s → the loop
        // exhausts maxPolls and falls through to UNKNOWN before the WaitWindow can return ACCEPTED_WITH_TASK.
        int maxPolls = 200;
        for (int i = 0; i < maxPolls; i++) {
            var timedOut = window.checkTimeout(System.currentTimeMillis());
            if (timedOut.isPresent()) {
                InvocationResponseStatus status = timedOut.get();
                String body = statusBody(status, window.taskId(), null);
                log.info("forwardSync corrId={} TIMEOUT→{} taskId={}", correlationId, status, window.taskId());
                g4w.onFold(status, ctx.tenantId(), ctx.messageId(), body);
                return ResponseEntity.ok().body(body);
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isEmpty()) {
                continue;
            }
            var event = proj.get();
            var fctx = new SyncFoldCtx(ctx, g4w, window, chosen, correlationId);
            Optional<String> folded = foldSyncProjection(fctx, event);
            if (folded.isPresent()) {
                return ResponseEntity.ok().body(folded.get());
            }
        }
        String unknownBody = statusBody(InvocationResponseStatus.UNKNOWN, null, null);
        log.info("forwardSync corrId={} UNKNOWN (no projection matched within accept+response window)", correlationId);
        g4w.onFold(InvocationResponseStatus.UNKNOWN, ctx.tenantId(), ctx.messageId(), unknownBody);
        return ResponseEntity.ok().body(unknownBody);
    }

    /**
     * Folds a single sync-create projection: binds sticky (P-13), tracks accept, and returns a
     * terminal/input-required body to surface to the client, or empty to keep polling.
     *
     * @param fctx sync fold context (governance, G4 wiring, window, chosen route, correlation id)
     * @param event the polled projection event
     * @return the folded body to return, or empty to continue polling
     */
    private Optional<String> foldSyncProjection(SyncFoldCtx fctx, ProjectionFeed.ProjectionEvent event) {
        GovernanceContext ctx = fctx.ctx();
        G4BusWiring g4w = fctx.g4w();
        WaitWindow window = fctx.window();
        AgentCardRoute chosen = fctx.chosen();
        String correlationId = fctx.correlationId();
        // P-13: bind taskId -> chosen routeHandle on the first taskId-bearing projection (mirrors
        // DIRECT Router.routeCreate, which writes sticky from the response taskId). The BUS
        // "response" arrives as projections; any taskId-bearing projection (ACCEPTED /
        // INPUT_REQUIRED / RESPONSE / TERMINAL) binds the owner so a later resume re-routes to it.
        if (event.taskId() != null && !event.taskId().isBlank()) {
            stickyIndex.put(event.taskId(), chosen.routeHandle(), chosen.targetServiceId());
        }
        InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
        if (folded == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
            window.onProjection(folded, event.taskId(), System.currentTimeMillis());
            return Optional.empty();
        }
        if (FiveStateFolder.isTerminal(folded) || folded == InvocationResponseStatus.INPUT_REQUIRED) {
            // terminal or wait-for-input: surface to the client and end the blocking call
            String taskId = event.taskId() != null ? event.taskId() : window.taskId();
            String body = statusBody(folded, taskId, event.body());
            log.info("forwardSync corrId={} folded={} taskId={} bodyPresent={}",
                    correlationId, folded, taskId, event.body() != null);
            g4w.onFold(folded, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        }
        // non-terminal non-accept (e.g. STREAM_READY): keep polling
        return Optional.empty();
    }

    /**
     * Runs the BUS streaming create path (FEAT-012 IN-4): enqueue control event, poll for
     * ACCEPTED + STREAM_READY projections, then bridge point-to-point SSE to the client.
     *
     * <p>Flow: search RDC → enqueue → poll ACCEPTED(taskId) → poll STREAM_READY(streamRef) →
     * resolve routeHandle → connect runtime SSE via SubscribeToTask + X-OpenJiuwen-Stream-Ref →
     * bridge SSE frames to the client's HttpServletResponse.
     *
     * @param ctx governance context (tenant, agent, message, trace, body)
     * @param response servlet response for writing the SSE stream
     * @param sseBridge SSE bridge (path-agnostic, reused from DIRECT)
     * @return empty if SSE was written to the response; a non-empty error/status body if
     *         STREAM_READY was not reached (timeout, rejection, failure)
     * @throws GovernanceException when no routable instance or enqueue fails
     * @throws IOException when writing the SSE stream fails (client disconnect)
     */
    public Optional<String> forwardStreaming(GovernanceContext ctx, HttpServletResponse response, SseBridge sseBridge)
            throws IOException {
        String effectiveAgentId = ctx.agentId() != null ? ctx.agentId() : defaultAgentResolver.resolve();
        List<AgentCardRoute> candidates = rdc.searchInstancesByAgentId(ctx.tenantId(), effectiveAgentId);
        if (candidates.isEmpty()) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NO_CANDIDATES",
                    "No routable instance for agent " + effectiveAgentId);
        }
        AgentCardRoute chosen = Router.selectByWeight(candidates);

        ForwardingEnvelope env = control.forward(ctx, chosen.routeHandle(), chosen.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardStreaming start corrId={} tenant={} target={}",
                correlationId, ctx.tenantId(), chosen.targetServiceId());

        long now = System.currentTimeMillis();
        WaitWindow window = new WaitWindow(now, acceptWindowMillis, responseWindowMillis);
        G4BusWiring g4w = new G4BusWiring(g4);

        StreamReadyOutcome outcome = pollForStreamReady(ctx, window, g4w, correlationId, chosen);
        if (outcome.earlyReturnBody() != null) {
            return Optional.of(outcome.earlyReturnBody());
        }
        StreamingCtx sctx = new StreamingCtx(ctx, response, sseBridge, g4w, correlationId);
        return bridgeStreamToClient(sctx, chosen, window, outcome.streamReadyEvent());
    }

    /**
     * Polls the projection feed until STREAM_READY, a terminal/INPUT_REQUIRED status, timeout, or
     * the poll budget is exhausted. Folds and returns an early body for every non-STREAM_READY
     * outcome; returns the STREAM_READY event otherwise.
     *
     * @param ctx governance context (tenant/message for folding)
     * @param window accept/response timeout window
     * @param g4w G4 wiring (fold callbacks)
     * @param correlationId correlation id to match projections
     * @param chosen chosen agent route (P-13: bound to taskId in StickyIndex on the first
     *              taskId-bearing projection, mirroring DIRECT Router.routeStream)
     * @return a STREAM_READY outcome, or an early-return body outcome (already folded)
     */
    private StreamReadyOutcome pollForStreamReady(GovernanceContext ctx, WaitWindow window, G4BusWiring g4w,
                                                  String correlationId, AgentCardRoute chosen) {
        int maxPolls = 100;
        for (int i = 0; i < maxPolls; i++) {
            var timedOut = window.checkTimeout(System.currentTimeMillis());
            if (timedOut.isPresent()) {
                InvocationResponseStatus status = timedOut.get();
                String body = statusBody(status, window.taskId(), null);
                log.info("forwardStreaming corrId={} TIMEOUT→{} taskId={}", correlationId, status, window.taskId());
                g4w.onFold(status, ctx.tenantId(), ctx.messageId(), body);
                return new StreamReadyOutcome(null, body);
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isEmpty()) {
                continue;
            }
            var event = proj.get();
            // P-13: bind taskId -> chosen routeHandle on the first taskId-bearing projection (mirrors
            // DIRECT Router.routeStream, which writes sticky on the first taskId frame).
            if (event.taskId() != null && !event.taskId().isBlank()) {
                stickyIndex.put(event.taskId(), chosen.routeHandle(), chosen.targetServiceId());
            }
            InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
            if (folded == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
                window.onProjection(folded, event.taskId(), System.currentTimeMillis());
            } else if (folded == InvocationResponseStatus.STREAM_READY) {
                return new StreamReadyOutcome(event, null);
            } else if (FiveStateFolder.isTerminal(folded)
                    || folded == InvocationResponseStatus.INPUT_REQUIRED) {
                String taskId = event.taskId() != null ? event.taskId() : window.taskId();
                String body = statusBody(folded, taskId, event.body());
                log.info("forwardStreaming corrId={} folded={} taskId={}", correlationId, folded, taskId);
                g4w.onFold(folded, ctx.tenantId(), ctx.messageId(), body);
                return new StreamReadyOutcome(null, body);
            } else {
                // non-terminal non-accept (e.g. other response): keep polling
                continue;
            }
        }
        String unknownBody = statusBody(InvocationResponseStatus.UNKNOWN, null, null);
        log.info("forwardStreaming corrId={} UNKNOWN (no STREAM_READY within window)", correlationId);
        g4w.onFold(InvocationResponseStatus.UNKNOWN, ctx.tenantId(), ctx.messageId(), unknownBody);
        return new StreamReadyOutcome(null, unknownBody);
    }

    /**
     * Resolves the SSE route, opens the runtime stream, and bridges frames to the client.
     *
     * @param sctx streaming context (governance, response, SSE bridge, G4, correlation id)
     * @param chosen chosen agent route
     * @param window accept/response window (released after route resolve)
     * @param streamReadyEvent the STREAM_READY projection event (carries streamRef/taskId)
     * @return empty if SSE was written; a non-empty FAILED body on any bridge failure
     * @throws IOException if writing the SSE stream fails
     */
    private Optional<String> bridgeStreamToClient(StreamingCtx sctx, AgentCardRoute chosen, WaitWindow window,
                                                  ProjectionFeed.ProjectionEvent streamReadyEvent) throws IOException {
        GovernanceContext ctx = sctx.ctx();
        G4BusWiring g4w = sctx.g4w();
        String correlationId = sctx.correlationId();
        String streamRef = streamReadyEvent.streamRef();
        String taskId = streamReadyEvent.taskId() != null ? streamReadyEvent.taskId() : window.taskId();
        log.info("forwardStreaming corrId={} STREAM_READY streamRef={} taskId={}", correlationId, streamRef, taskId);
        if (streamRef == null || streamRef.isBlank() || taskId == null) {
            String body = statusBody(InvocationResponseStatus.FAILED, taskId,
                    "STREAM_READY without streamRef or taskId");
            g4w.onFold(InvocationResponseStatus.FAILED, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        }
        ResolvedRoute resolved;
        try {
            resolved = rdc.resolveRouteHandle(chosen.routeHandle(), ctx.tenantId());
        } catch (RouteResolutionException ex) {
            String body = statusBody(InvocationResponseStatus.FAILED, taskId, "Cannot resolve route for SSE bridge");
            g4w.onFold(InvocationResponseStatus.FAILED, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        }
        log.info("forwardStreaming corrId={} resolved endpoint={}", correlationId, resolved.endpointUrl());
        window.release();

        Stream<String> frames;
        try {
            frames = agentRuntimeClient.openStreamByRef(resolved.endpointUrl(), streamRef, taskId, ctx.tenantId());
        } catch (GovernanceException ex) {
            // Runtime rejected SubscribeToTask (e.g. HTTP 4xx). Surface the reason (status + first
            // body line, captured by HttpAgentRuntimeClient) in a FAILED body before the response is
            // committed — and log it, so the cause is visible (the global handler would log nothing).
            log.warn("forwardStreaming corrId={} openStreamByRef rejected: {}", correlationId, ex.getMessage());
            String body = statusBody(InvocationResponseStatus.FAILED, taskId, ex.getMessage());
            g4w.onFold(InvocationResponseStatus.FAILED, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        }
        log.info("forwardStreaming corrId={} openStreamByRef returned; reading first frame (deadline {}ms)",
                correlationId, streamFirstFrameDeadlineMillis);
        // try-with-resources closes the stream on every path (incl. timeout) to unblock the runtime.
        try (Stream<String> fr = frames) {
            return drainStreamToClient(sctx, taskId, fr, window);
        }
    }

    /**
     * Reads the first frame with a deadline, then commits the SSE response and drains the rest.
     *
     * <p>After draining the runtime data stream, polls the projection feed for the TERMINAL
     * projection (within the response window) and writes a synthesized terminal task frame so
     * the client SDK folds a real terminal state (COMPLETED/FAILED/REJECTED) instead of falling
     * back to its stream-end default (which would surface WORKING — the runtime's SubscribeToTask
     * stream carries only data chunks, never a terminal task surface; the terminal state travels
     * as a bus INVOCATION_TERMINAL event to the gateway, not into the client SSE).
     *
     * @param sctx streaming context (governance, response, SSE bridge, G4, correlation id)
     * @param taskId task id for status bodies
     * @param frames the runtime SSE frame stream (closed by the caller's try-with-resources)
     * @param window accept/response window (already released for routing; responseWindowMillis
     *               bounds the terminal-projection wait here)
     * @return empty if SSE was written; a non-empty FAILED body if the first-frame read fails
     * @throws IOException if writing the SSE stream fails
     */
    private Optional<String> drainStreamToClient(StreamingCtx sctx, String taskId, Stream<String> frames,
                                                 WaitWindow window) throws IOException {
        GovernanceContext ctx = sctx.ctx();
        HttpServletResponse response = sctx.response();
        G4BusWiring g4w = sctx.g4w();
        String correlationId = sctx.correlationId();
        Iterator<String> frameIterator = frames.iterator();
        String firstFrame;
        try {
            firstFrame = readFirstFrameOrTimeout(frameIterator);
        } catch (TimeoutException ex) {
            log.warn("forwardStreaming corrId={} STREAM_DEADLINE_EXCEEDED (no first frame within {}ms)",
                    correlationId, streamFirstFrameDeadlineMillis);
            String body = statusBody(InvocationResponseStatus.FAILED, taskId, "STREAM_DEADLINE_EXCEEDED");
            g4w.onFold(InvocationResponseStatus.FAILED, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        } catch (GovernanceException ex) {
            log.warn("forwardStreaming corrId={} stream read failed: {}", correlationId, ex.getMessage());
            String body = statusBody(InvocationResponseStatus.FAILED, taskId, ex.getMessage());
            g4w.onFold(InvocationResponseStatus.FAILED, ctx.tenantId(), ctx.messageId(), body);
            return Optional.of(body);
        }
        log.info("forwardStreaming corrId={} firstFrame obtained present={}", correlationId, firstFrame != null);

        // First frame obtained (or stream empty) — commit the SSE response and drain the rest.
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        // BUS 流式:runtime 的 SubscribeToTask 流只产 data chunk(artifact-update),不再发带 taskId 的
        // task 面——taskId 已作为 bus 事件(INVOCATION_ACCEPTED)先到 gateway 投影。客户端 SDK 的
        // accepted() 靠"首帧带 id"绑定(bindTaskRef),无 id 永不结算 → accepted() 超时(Feat-Func-006)。
        // 故在 data 流之前先合成一帧 task 面(同 DIRECT 首帧格式,见 Feat-Func-011 §),把 taskId 交给客户端。
        String acceptFrame = acceptTaskFrame(taskId, ctx.contextId());
        ProjectionFeed.ProjectionEvent terminalEvent;
        try {
            OutputStream out = response.getOutputStream();
            sctx.sseBridge().writeSse(out, acceptFrame);                 // 先写合成的 task 面(A2A v1.0 {"task":{...}})
            // issue-S1 (drain 并发收尾): pre-drain projection check. If INPUT_REQUIRED/TERMINAL has
            // already been routed to staging (dispatcher), surface it NOW — skip the runtime SSE drain.
            // Some runtimes end the stream after an interrupt but don't close the HTTP response → the
            // drain (frameIterator.hasNext) would block forever. The projection is the authoritative
            // signal; the runtime SSE is just data passthrough (skipped when the projection is ready).
            Optional<ProjectionFeed.ProjectionEvent> early = pollEarlyTerminal(sctx);
            if (early.isPresent()) {
                terminalEvent = early.get();
            } else {
                sctx.sseBridge().writeSse(out, frameIterator, firstFrame);  // 再透传 runtime data 流
                terminalEvent = pollTerminalEvent(sctx, taskId, window);
            }
            // 不合成、不改写——TERMINAL/INPUT_REQUIRED 投影的 body 已是 Runtime
            // 使用 HTTP 入口同源序列化器生成的完整 JSON-RPC response。
            String terminalFrame = terminalTaskFrame(terminalEvent);
            sctx.sseBridge().writeSse(out, terminalFrame);
        } catch (IOException ex) {
            // SSE disconnected (client Ctrl+C or runtime Connection-reset) — response is already
            // committed (text/event-stream), so rethrowing only produces a noisy Tomcat ERROR +
            // "no converter" WARN. Abort G4 (the create failed), log, and let Spring close the
            // response. SseBridge already logged the direction-specific bridge release.
            g4w.onAbort(ctx.tenantId(), ctx.messageId());
            log.info("forwardStreaming corrId={} SSE stream closed after disconnect", correlationId);
            return Optional.empty();
        }
        InvocationResponseStatus folded = FiveStateFolder.fold(terminalEvent.eventType());
        String replayResult = terminalEvent.body() != null ? terminalEvent.body()
                : (firstFrame != null ? firstFrame : "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"completed\"}}");
        g4w.onFold(folded, ctx.tenantId(), ctx.messageId(), replayResult);
        log.info("forwardStreaming corrId={} stream done folded={}", correlationId, folded);
        return Optional.empty();
    }

    /**
     * Pre-drain projection check (issue-S1): poll ONCE for a terminal / INPUT_REQUIRED projection.
     * If one is already staged (the dispatcher routed it before the drain), surface it and SKIP the
     * runtime SSE drain — the drain would block forever on a runtime that ends the stream after an
     * interrupt but doesn't close the HTTP response. Non-terminal projections (e.g., repeated
     * ACCEPTED/STREAM_READY) are consumed + ignored (the drain proceeds for data passthrough).
     *
     * @param sctx streaming context (for the correlation id)
     * @return the early terminal/input-required projection, or empty if none ready
     */
    private Optional<ProjectionFeed.ProjectionEvent> pollEarlyTerminal(StreamingCtx sctx) {
        Optional<ProjectionFeed.ProjectionEvent> proj = projectionFeed.poll(sctx.correlationId());
        if (proj.isEmpty()) {
            return Optional.empty();
        }
        InvocationResponseStatus folded = FiveStateFolder.fold(proj.get().eventType());
        if (FiveStateFolder.isTerminal(folded) || folded == InvocationResponseStatus.INPUT_REQUIRED) {
            log.info("forwardStreaming corrId={} early terminal/input-required projection "
                    + "matched folded={} taskId={} (runtime SSE drain skipped)",
                    sctx.correlationId(), folded, proj.get().taskId());
            return proj;
        }
        return Optional.empty();
    }

    /**
     * 在 response 窗口内轮询 TERMINAL 投影。runtime data 流已结束时,终态(COMPLETED/FAILED/REJECTED)
     * 仍需经 bus 两跳 INVOCATION_TERMINAL 事件到达 gateway;在窗口内等到即返回该投影事件(携带 runtime
     * 产出的完整 A2A Task),超时则返回一个合成的 COMPLETED 投影(body=null)。
     *
     * @param sctx streaming context
     * @param taskId task id(诊断)
     * @param window 用于取 responseWindowMillis 上界(已 released,checkTimeout 返回 empty)
     * @return 终态投影事件(永不返回 null)
     */
    private ProjectionFeed.ProjectionEvent pollTerminalEvent(StreamingCtx sctx, String taskId, WaitWindow window) {
        String correlationId = sctx.correlationId();
        long deadline = System.currentTimeMillis() + responseWindowMillis;
        int maxPolls = 100;
        for (int i = 0; i < maxPolls; i++) {
            if (System.currentTimeMillis() >= deadline) {
                log.info("forwardStreaming corrId={} terminal poll timeout (no TERMINAL within {}ms), assume COMPLETED",
                        correlationId, responseWindowMillis);
                return new ProjectionFeed.ProjectionEvent(
                        AgentBusEventType.INVOCATION_TERMINAL, taskId, null, null, null);
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isEmpty()) {
                continue;
            }
            var event = proj.get();
            InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
            // issue-A: stop on INPUT_REQUIRED too — a streaming task that goes input-required
            // mid-stream will never emit a TERMINAL; waiting responseWindowMillis would empty-wait
            // + synthesize a wrong COMPLETED. Return the INPUT_REQUIRED event so its body (the
            // input-required task) is enveloped as the terminal frame and the client folds
            // INPUT_REQUIRED (execute tools / resume) instead of a spurious COMPLETED.
            if (FiveStateFolder.isTerminal(folded) || folded == InvocationResponseStatus.INPUT_REQUIRED) {
                log.info("forwardStreaming corrId={} terminal/input-required projection matched folded={} taskId={}",
                        correlationId, folded, event.taskId());
                return event;
            }
            // 非终态投影(如重复 ACCEPTED/STREAM_READY)继续轮询
        }
        log.info("forwardStreaming corrId={} terminal poll budget exhausted, assume COMPLETED", correlationId);
        return new ProjectionFeed.ProjectionEvent(
                AgentBusEventType.INVOCATION_TERMINAL, taskId, null, null, null);
    }

    /**
     * 原样透传 Runtime 产出的完整 A2A JSON-RPC response。
     * Gateway 不改写 Task 内容，也不重建 result 或 JSON-RPC envelope。
     * 若 body 为 null(超时兜底),合成一个最小终态帧(A2A v1.0 {"task":{...}} 格式)。
     *
     * @param terminalEvent TERMINAL 投影事件(可能携带 runtime 的 a2aResponse)
     * @return JSON-RPC envelope 字符串
     */
    private static String terminalTaskFrame(ProjectionFeed.ProjectionEvent terminalEvent) {
        if (terminalEvent.body() != null && !terminalEvent.body().isBlank()) {
            // Runtime 已使用与 HTTP 入口相同的序列化器产出完整 JSON-RPC response。
            // Gateway 只原样透传，不重建 result 联合体或 envelope。
            return terminalEvent.body();
        }
        // 超时兜底:body 为 null,合成最小终态(A2A v1.0 {"task":{...}} 格式)
        String state = FiveStateFolder.fold(terminalEvent.eventType()) == InvocationResponseStatus.FAILED
                ? "TASK_STATE_FAILED" : "TASK_STATE_COMPLETED";
        return "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":\""
                + (terminalEvent.taskId() != null ? terminalEvent.taskId() : "")
                + "\",\"status\":{\"state\":\"" + state + "\"}}}}";
    }

    /**
     * 合成客户端可见的"已接受"task 面帧(BUS 流式专用)。runtime 的 SubscribeToTask 流不含
     * 带 {@code id} 的 task 面,客户端 SDK 据此帧的 {@code result.task.id} 绑定 taskRef 并结算
     * {@code accepted()}。格式对齐 A2A v1.0 spec({"task":{...}} 包装,同 runtime 直出格式)。
     *
     * @param taskId runtime 创建的真实 Task ID(来自 INVOCATION_ACCEPTED 投影)
     * @param contextId 会话上下文 ID
     * @return JSON-RPC task 面帧字符串
     */
    private static String acceptTaskFrame(String taskId, String contextId) {
        return "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":\""
                + (taskId != null ? taskId : "")
                + "\",\"contextId\":\""
                + (contextId != null ? contextId : "")
                + "\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}";
    }

    /**
     * Reads the first SSE frame on a worker thread, bounded by {@link #streamFirstFrameDeadlineMillis}.
     * On timeout the caller's try-with-resources closes the stream to unblock the runtime response.
     *
     * @param iterator the frame iterator (first element consumed here)
     * @return the first frame, or {@code null} if the stream ended empty
     * @throws TimeoutException      if no first frame arrives within the deadline
     * @throws GovernanceException   if the read is interrupted or fails
     */
    private String readFirstFrameOrTimeout(Iterator<String> iterator)
            throws TimeoutException, GovernanceException {
        Future<String> future = firstFrameExec.submit(() -> iterator.hasNext() ? iterator.next() : null);
        try {
            return future.get(streamFirstFrameDeadlineMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);   // interrupt the blocked first-frame read
            throw te;
        } catch (InterruptedException ie) {
            // G.CON.10: do not re-interrupt; surface as a governance error instead.
            future.cancel(true);
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_INTERRUPTED",
                    "Stream read interrupted");
        } catch (ExecutionException ee) {
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Runtime stream read failed", ee.getCause());
        }
    }

    /**
     * Builds the client-facing status body. A completed projection already contains the complete
     * JSON-RPC response produced by the Runtime and is forwarded unchanged. Other statuses return
     * a synthesized JSON-RPC envelope {@code {"jsonrpc":"2.0","result":{"status":...}}}, with
     * {@code taskId} when known and {@code reason} for REJECTED/FAILED. The {@code jsonrpc}
     * envelope mirrors the streaming terminal-frame fallback so ACCEPTED_WITH_TASK / UNKNOWN
     * (and the other non-completed states) are not emitted as bare {@code {"result":...}}.
     *
     * @param s folded invocation status
     * @param taskId task id when known (ACCEPTED_WITH_TASK / INPUT_REQUIRED / accepted-then-response)
     * @param body decoded A2A response (RESPONSE/TERMINAL) or reason (REJECTED/FAILED)
     * @return the HTTP response body
     */
    private static String statusBody(InvocationResponseStatus s, String taskId, String body) {
        if (s == InvocationResponseStatus.COMPLETED_RESPONSE && body != null && !body.isBlank()) {
            return body;
        }
        // S9 (v0830): UNKNOWN → JSON-RPC error (PROJECTION_TIMEOUT_UNKNOWN, retryable=true)
        // per A1 — method-result error, not a fake "result:UNKNOWN" status.
        if (s == InvocationResponseStatus.UNKNOWN) {
            return jsonError(ErrorCodes.PROJECTION_TIMEOUT_UNKNOWN, "accept window timeout, task unknown", null);
        }
        // R13 (B 倾向): ACCEPTED_WITH_TASK → synthesized A2A Task placeholder (TASK_STATE_SUBMITTED),
        // not the internal five-state name.
        if (s == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
            return "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":\""
                    + (taskId != null ? taskId : "")
                    + "\",\"status\":{\"state\":\"TASK_STATE_SUBMITTED\"}}}}";
        }
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"");
        sb.append(s.name()).append("\"");
        if (taskId != null && !taskId.isBlank()) {
            sb.append(",\"taskId\":\"").append(taskId).append("\"");
        }
        if ((s == InvocationResponseStatus.REJECTED || s == InvocationResponseStatus.FAILED)
                && body != null && !body.isBlank()) {
            sb.append(",\"reason\":\"").append(body).append("\"");
        }
        sb.append("}}");
        return sb.toString();
    }

    /**
     * Serialize a {@link JsonRpcError} envelope for a method-result error.
     *
     * @param ec stable error code
     * @param message human-readable message
     * @param requestId JSON-RPC request id (may be null)
     * @return JSON string of the error envelope
     */
    private static String jsonError(ErrorCodes ec, String message, String requestId) {
        try {
            return ERROR_MAPPER.writeValueAsString(JsonRpcError.of(requestId, ec, message));
        } catch (JsonProcessingException ex) {
            // Fallback: minimal manual envelope
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + ec.numericCode()
                    + ",\"message\":\"" + message + "\",\"data\":{\"code\":\""
                    + ec.stableCode() + "\",\"retryable\":" + ec.retryable() + "}}}";
        }
    }

    /** Outcome of the STREAM_READY poll: either a stream-ready event or an already-folded early body. */
    private record StreamReadyOutcome(ProjectionFeed.ProjectionEvent streamReadyEvent, String earlyReturnBody) {
    }

    /** Bundles the streaming request context carried through the bridge/drain helpers. */
    private record StreamingCtx(GovernanceContext ctx, HttpServletResponse response, SseBridge sseBridge,
                                G4BusWiring g4w, String correlationId) {
    }

    /** Bundles the SubscribeToTask bridge context (response/SSE/owner/correlation) carried into bridgeSubscribeStream. */
    private record SubscribeBridgeCtx(GovernanceContext ctx, HttpServletResponse response, SseBridge sseBridge,
                                      StickyIndex.Owner owner, String correlationId) {
    }

    /** Bundles the sync-create fold context (G4/window/chosen/correlation) carried into foldSyncProjection. */
    private record SyncFoldCtx(GovernanceContext ctx, G4BusWiring g4w, WaitWindow window,
                               AgentCardRoute chosen, String correlationId) {
    }
}
