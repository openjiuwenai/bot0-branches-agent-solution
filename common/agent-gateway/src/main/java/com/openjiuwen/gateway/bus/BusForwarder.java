/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.ProjectionFeed;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;
import com.openjiuwen.gateway.bus.wait.G4BusWiring;
import com.openjiuwen.gateway.bus.wait.WaitWindow;
import com.openjiuwen.gateway.direct.AgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.DefaultAgentResolver;
import com.openjiuwen.gateway.routing.RdcRouteClient;
import com.openjiuwen.gateway.routing.ResolvedRoute;
import com.openjiuwen.gateway.routing.RouteResolutionException;
import com.openjiuwen.gateway.sse.SseBridge;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    private final RdcRouteClient rdc;
    private final BusControlForwarder control;
    private final ProjectionFeed projectionFeed;
    private final IdempotencyRule g4;
    private final String sourceServiceId;
    private final long acceptWindowMillis;
    private final long responseWindowMillis;
    private final AgentRuntimeClient agentRuntimeClient;
    private final DefaultAgentResolver defaultAgentResolver;
    private long streamFirstFrameDeadlineMillis = 10_000L;

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
     */
    public BusForwarder(RdcRouteClient rdc, BusControlForwarder control, ProjectionFeed projectionFeed,
                        IdempotencyRule g4, String sourceServiceId,
                        long acceptWindowMillis, long responseWindowMillis,
                        AgentRuntimeClient agentRuntimeClient, DefaultAgentResolver defaultAgentResolver) {
        this.rdc = rdc;
        this.control = control;
        this.projectionFeed = projectionFeed;
        this.g4 = g4;
        this.sourceServiceId = sourceServiceId;
        this.acceptWindowMillis = acceptWindowMillis;
        this.responseWindowMillis = responseWindowMillis;
        this.agentRuntimeClient = agentRuntimeClient;
        this.defaultAgentResolver = defaultAgentResolver;
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
        AgentCardRoute chosen = candidates.get(0);

        ForwardingEnvelope env = control.forward(ctx, chosen.routeHandle(), chosen.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardSync start corrId={} tenant={} target={}",
                correlationId, ctx.tenantId(), chosen.targetServiceId());

        long now = System.currentTimeMillis();
        WaitWindow window = new WaitWindow(now, acceptWindowMillis, responseWindowMillis);
        G4BusWiring g4w = new G4BusWiring(g4);

        int maxPolls = 100;
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
            InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
            if (folded == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
                window.onProjection(folded, event.taskId(), System.currentTimeMillis());
            } else if (FiveStateFolder.isTerminal(folded)
                    || folded == InvocationResponseStatus.INPUT_REQUIRED) {
                // terminal or wait-for-input: surface to the client and end the blocking call
                String taskId = event.taskId() != null ? event.taskId() : window.taskId();
                String body = statusBody(folded, taskId, event.body());
                log.info("forwardSync corrId={} folded={} taskId={} bodyPresent={}",
                        correlationId, folded, taskId, event.body() != null);
                g4w.onFold(folded, ctx.tenantId(), ctx.messageId(), body);
                return ResponseEntity.ok().body(body);
            } else {
                // non-terminal non-accept (e.g. STREAM_READY): keep polling
            }
        }
        String unknownBody = statusBody(InvocationResponseStatus.UNKNOWN, null, null);
        log.info("forwardSync corrId={} UNKNOWN (no projection matched within accept+response window)", correlationId);
        g4w.onFold(InvocationResponseStatus.UNKNOWN, ctx.tenantId(), ctx.messageId(), unknownBody);
        return ResponseEntity.ok().body(unknownBody);
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
        AgentCardRoute chosen = candidates.get(0);

        ForwardingEnvelope env = control.forward(ctx, chosen.routeHandle(), chosen.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardStreaming start corrId={} tenant={} target={}",
                correlationId, ctx.tenantId(), chosen.targetServiceId());

        long now = System.currentTimeMillis();
        WaitWindow window = new WaitWindow(now, acceptWindowMillis, responseWindowMillis);
        G4BusWiring g4w = new G4BusWiring(g4);

        StreamReadyOutcome outcome = pollForStreamReady(ctx, window, g4w, correlationId);
        if (outcome.earlyReturnBody() != null) {
            return Optional.of(outcome.earlyReturnBody());
        }
        return bridgeStreamToClient(ctx, response, sseBridge, g4w, correlationId, chosen, window,
                outcome.streamReadyEvent());
    }

    /**
     * Polls the projection feed until STREAM_READY, a terminal/INPUT_REQUIRED status, timeout, or
     * the poll budget is exhausted. Folds and returns an early body for every non-STREAM_READY
     * outcome; returns the STREAM_READY event otherwise.
     */
    private StreamReadyOutcome pollForStreamReady(GovernanceContext ctx, WaitWindow window, G4BusWiring g4w,
                                                  String correlationId) {
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
     * @return empty if SSE was written; a non-empty FAILED body on any bridge failure
     */
    private Optional<String> bridgeStreamToClient(GovernanceContext ctx, HttpServletResponse response,
                                                  SseBridge sseBridge, G4BusWiring g4w, String correlationId,
                                                  AgentCardRoute chosen, WaitWindow window,
                                                  ProjectionFeed.ProjectionEvent streamReadyEvent) throws IOException {
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
            return drainStreamToClient(ctx, response, sseBridge, g4w, correlationId, taskId, fr);
        }
    }

    /**
     * Reads the first frame with a deadline, then commits the SSE response and drains the rest.
     *
     * @return empty if SSE was written; a non-empty FAILED body if the first-frame read fails
     */
    private Optional<String> drainStreamToClient(GovernanceContext ctx, HttpServletResponse response,
                                                 SseBridge sseBridge, G4BusWiring g4w, String correlationId,
                                                 String taskId, Stream<String> frames) throws IOException {
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
        String replayResult = firstFrame != null ? firstFrame
                : "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"completed\"}}";
        try {
            sseBridge.writeSse(response.getOutputStream(), frameIterator, firstFrame);
        } catch (IOException ex) {
            g4w.onAbort(ctx.tenantId(), ctx.messageId());
            throw ex;
        }
        g4w.onFold(InvocationResponseStatus.COMPLETED_RESPONSE, ctx.tenantId(), ctx.messageId(), replayResult);
        log.info("forwardStreaming corrId={} stream done", correlationId);
        return Optional.empty();
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
     * Builds the client-facing status body. COMPLETED_RESPONSE with a decoded A2A response
     * returns that response directly (the gateway forwards the A2A JSON-RPC response); other
     * statuses return {@code {"result":{"status":...}}}, with {@code taskId} when known and
     * {@code reason} for REJECTED/FAILED.
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
        StringBuilder sb = new StringBuilder("{\"result\":{\"status\":\"").append(s.name()).append("\"");
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

    /** Outcome of the STREAM_READY poll: either a stream-ready event or an already-folded early body. */
    private record StreamReadyOutcome(ProjectionFeed.ProjectionEvent streamReadyEvent, String earlyReturnBody) {
    }
}
