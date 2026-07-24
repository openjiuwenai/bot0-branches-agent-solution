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

        long now = System.currentTimeMillis();
        WaitWindow window = new WaitWindow(now, acceptWindowMillis, responseWindowMillis);
        G4BusWiring g4w = new G4BusWiring(g4);

        int maxPolls = 100;
        for (int i = 0; i < maxPolls; i++) {
            InvocationResponseStatus status = window.checkTimeout(System.currentTimeMillis());
            if (status != null) {
                g4w.onFold(status, ctx.tenantId(), ctx.messageId(), statusBody(status));
                return ResponseEntity.ok().body(statusBody(status));
            }
            var proj = projectionFeed.poll(correlationId);
            if (proj.isPresent()) {
                var event = proj.get();
                InvocationResponseStatus folded = FiveStateFolder.fold(event.eventType());
                if (folded == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
                    window.onProjection(folded, event.taskId(), System.currentTimeMillis());
                } else if (FiveStateFolder.isTerminal(folded)) {
                    window.onProjection(folded, null, System.currentTimeMillis());
                } else {
                    /* no-op: exhaustive for current PathMode */
                }
            }
        }
        g4w.onFold(InvocationResponseStatus.UNKNOWN, ctx.tenantId(), ctx.messageId(), null);
        return ResponseEntity.ok().body(statusBody(InvocationResponseStatus.UNKNOWN));
    }

    private static String statusBody(InvocationResponseStatus s) {
        return "{\"result\":{\"status\":\"" + s.name() + "\"}}";
    }
}
