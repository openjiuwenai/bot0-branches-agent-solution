/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.EnvelopeBuilder;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;
import com.openjiuwen.gateway.bus.control.FakeProjectionFeed;
import com.openjiuwen.gateway.bus.control.InMemoryPayloadStore;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.DefaultAgentResolver;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.StickyIndex;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link BusForwarder} sync create path.
 *
 * @since 2026-07-24
 */
class BusForwarderTest {
    private final FakeRdcRouteClient rdc = new FakeRdcRouteClient();
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final FakeProjectionFeed feed = new FakeProjectionFeed();
    private final IdempotencyRule g4 = new IdempotencyRule();
    private final StickyIndex sticky = new StickyIndex();
    private final BusForwarder forwarder = new BusForwarder(rdc,
            new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
            feed, g4, "svc-gw", 30_000L, 60_000L, null, new DefaultAgentResolver("default-agent-1"), sticky);

    private GovernanceContext ctx(String agentId, String messageId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setAgentId(agentId);
        c.setMessageId(messageId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\"}");
        return c;
    }

    @Test
    void syncCreateReturnsCompletedResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void syncCreateAccepted() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-7", null);
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m2"));
        assertThat(resp.getBody()).contains("COMPLETED_RESPONSE");
    }

    @Test
    void syncCreateRejected() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_REJECTED, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m3"));
        assertThat(resp.getBody()).contains("REJECTED");
    }

    @Test
    void syncCreateFailed() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_FAILED, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m4"));
        assertThat(resp.getBody()).contains("FAILED");
    }

    @Test
    void emptyCandidatesRouteNoCandidates() {
        rdc.setCandidates(List.of());
        var thrown = catchThrowable(() -> forwarder.forwardSync(ctx("agent-1", "m5")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("ROUTE_NO_CANDIDATES");
        }
        assertThat(outbox.enqueued()).isEmpty();
    }

    @Test
    void produceFailEnqueueFailed() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        outbox.setFailNext(true);
        var thrown = catchThrowable(() -> forwarder.forwardSync(ctx("agent-1", "m6")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("ENQUEUE_FAILED");
        }
    }

    @Test
    void correlationIdSelfGenerated() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m7"));
        assertThat(outbox.enqueued()).hasSize(1);
        var env = outbox.enqueued().get(0);
        assertThat(env.correlationId()).startsWith("gw-correlation-");
        assertThat(env.correlationId()).doesNotContain("clientInvocationId");
    }

    @Test
    void envelopeTargetServiceIdFromRdc() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-target-9")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m8"));
        assertThat(outbox.enqueued().get(0).targetServiceId()).isEqualTo("svc-target-9");
    }

    @Test
    void noTopologyLeakInResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m9"));
        assertThat(resp.getBody()).doesNotContain("routeHandle", "endpoint", "topic", "worker");
    }

    @Test
    void g4CompleteAfterResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        g4.check("T1", "m10", "fp");
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m10"));
        assertThat(g4.isCompleted("T1", "m10")).contains(true);
    }

    @Test
    void syncCreateReturnsInputRequired() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        g4.check("T1", "m-ir", "fp");
        feed.inject(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "ti-1", null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m-ir"));
        assertThat(resp.getBody()).contains("INPUT_REQUIRED").contains("ti-1");
        assertThat(g4.isCompleted("T1", "m-ir")).contains(true);
    }

    @Test
    void completedResponseSurfacesA2aBody() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null, null,
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-9\",\"result\":{\"task\":{"
                        + "\"id\":\"t9\",\"status\":{\"state\":\"completed\"},"
                        + "\"artifacts\":[{\"parts\":[{\"text\":\"answer\"}]}]}}}");
        var resp = forwarder.forwardSync(ctx("agent-1", "m-body"));
        assertThat(resp.getBody()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"req-9\",\"result\":{\"task\":{"
                + "\"id\":\"t9\",\"status\":{\"state\":\"completed\"},"
                + "\"artifacts\":[{\"parts\":[{\"text\":\"answer\"}]}]}}}");
    }

    @Test
    void syncCreateNullAgentIdUsesDefault() {
        // FEAT-011 P0: a create with no agentId routes to the configured default agent,
        // mirroring the DIRECT Router. The BUS path must not pass null to RDC (which NPEs
        // in HttpRdcRouteClient.enc) — it must fall back to DefaultAgentResolver.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx(null, "m-da"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(rdc.lastAgentId()).isEqualTo("default-agent-1");
    }

    @Test
    void syncCreateBindsStickyOnAcceptedWithTask() {
        // P-13: BUS create must bind taskId -> routeHandle on a taskId-bearing projection (mirrors
        // DIRECT Router.routeCreate, which writes sticky from the response taskId), so a later resume
        // re-routes to the owning runtime instead of 404 RESUME_OWNER_UNKNOWN.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-7", null);
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m-sticky"));
        assertThat(sticky.find("task-7")).contains("h1");
    }

    @Test
    void syncCreateInputRequiredBindsStickyEvenWithoutAccepted() {
        // P-13: a create that returns INPUT_REQUIRED (taskId on the INPUT_REQUIRED projection, no prior
        // ACCEPTED) must STILL bind sticky — the resume path reads only StickyIndex, so a miss here
        // means the user's follow-up input can never resume (RESUME_OWNER_UNKNOWN).
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "ti-1", null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m-ir-sticky"));
        assertThat(resp.getBody()).contains("INPUT_REQUIRED").contains("ti-1");
        assertThat(sticky.find("ti-1")).contains("h1");
    }
}
