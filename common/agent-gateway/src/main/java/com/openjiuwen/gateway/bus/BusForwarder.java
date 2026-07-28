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
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.RdcRouteClient;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Orchestrates the BUS sync create path (FEAT-012 §4): search→enqueue→wait→fold→respond.
 *
 * @since 2026-07-24
 */
public class BusForwarder {
    private final RdcRouteClient rdc;
    private final BusControlForwarder control;
    private final ProjectionFeed projectionFeed;
    private final IdempotencyRule g4;
    private final String sourceServiceId;
    private final long acceptWindowMillis;
    private final long responseWindowMillis;
    private static final Logger log = LoggerFactory.getLogger(BusForwarder.class);

    /**
     * Creates a forwarder wired with RDC search, control enqueue, projection feed, and G4.
     *
     * @param rdc route discovery client
     * @param control I-04 outbound forwarder
     * @param projectionFeed inbound projection poll port
     * @param g4 idempotency rule (G4)
     * @param sourceServiceId gateway service identity for envelope audit
     * @param acceptWindowMillis accept-phase timeout window
     * @param responseWindowMillis response-phase timeout window after accept
     */
    public BusForwarder(RdcRouteClient rdc, BusControlForwarder control, ProjectionFeed projectionFeed,
                        IdempotencyRule g4, String sourceServiceId,
                        long acceptWindowMillis, long responseWindowMillis) {
        this.rdc = rdc;
        this.control = control;
        this.projectionFeed = projectionFeed;
        this.g4 = g4;
        this.sourceServiceId = sourceServiceId;
        this.acceptWindowMillis = acceptWindowMillis;
        this.responseWindowMillis = responseWindowMillis;
    }

    /**
     * Runs the BUS sync create path: search RDC, enqueue, poll projections, fold to five states.
     *
     * @param ctx governance context (tenant, agent, message, trace, body)
     * @return HTTP 200 with folded status JSON body
     * @throws GovernanceException when no routable instance or enqueue fails
     */
    public ResponseEntity<String> forwardSync(GovernanceContext ctx) {
        String effectiveAgentId = ctx.agentId() != null ? ctx.agentId() : null;
        List<AgentCardRoute> candidates = rdc.searchInstancesByAgentId(ctx.tenantId(), effectiveAgentId);
        if (candidates.isEmpty()) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NO_CANDIDATES",
                    "No routable instance for agent " + effectiveAgentId);
        }
        AgentCardRoute chosen = candidates.get(0);

        ForwardingEnvelope env = control.forward(ctx, chosen.routeHandle(), chosen.targetServiceId(),
                sourceServiceId, System.currentTimeMillis() + 30000);
        String correlationId = env.correlationId();
        log.info("forwardSync start corrId={} tenant={} target={}", correlationId, ctx.tenantId(), chosen.targetServiceId());

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
                log.info("forwardSync corrId={} folded={} taskId={} bodyPresent={}", correlationId, folded, taskId, event.body() != null);
                g4w.onFold(folded, ctx.tenantId(), ctx.messageId(), body);
                return ResponseEntity.ok().body(body);
            }
            // non-terminal non-accept (e.g. STREAM_READY): keep polling
        }
        String unknownBody = statusBody(InvocationResponseStatus.UNKNOWN, null, null);
        log.info("forwardSync corrId={} UNKNOWN (no projection matched within accept+response window)", correlationId);
        g4w.onFold(InvocationResponseStatus.UNKNOWN, ctx.tenantId(), ctx.messageId(), unknownBody);
        return ResponseEntity.ok().body(unknownBody);
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
}
