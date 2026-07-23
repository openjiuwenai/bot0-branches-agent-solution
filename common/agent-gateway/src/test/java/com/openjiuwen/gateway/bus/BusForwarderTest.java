package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.EnvelopeBuilder;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;
import com.openjiuwen.gateway.bus.control.FakeProjectionFeed;
import com.openjiuwen.gateway.bus.control.InMemoryPayloadStore;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;

class BusForwarderTest {
    private final FakeRdcRouteClient rdc = new FakeRdcRouteClient();
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final FakeProjectionFeed feed = new FakeProjectionFeed();
    private final IdempotencyRule g4 = new IdempotencyRule();
    private final BusForwarder forwarder = new BusForwarder(rdc,
            new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
            feed, g4, "svc-gw", 30_000L, 60_000L);

    private GovernanceContext ctx(String agentId, String messageId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setAgentId(agentId);
        c.setMessageId(messageId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\"}");
        return c;
    }

    @Test void syncCreateReturnsCompletedResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        // poll matches any correlationId → inject with wildcard
        var resp = forwarder.forwardSync(ctx("agent-1", "m1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test void syncCreateAccepted() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-7", null);
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m2"));
        assertThat(resp.getBody()).contains("COMPLETED_RESPONSE");
    }

    @Test void syncCreateRejected() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_REJECTED, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m3"));
        assertThat(resp.getBody()).contains("REJECTED");
    }

    @Test void syncCreateFailed() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_FAILED, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m4"));
        assertThat(resp.getBody()).contains("FAILED");
    }

    @Test void emptyCandidatesRouteNoCandidates() {
        rdc.setCandidates(List.of());
        var thrown = catchThrowable(() -> forwarder.forwardSync(ctx("agent-1", "m5")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        assertThat(((GovernanceException) thrown).code()).isEqualTo("ROUTE_NO_CANDIDATES");
        assertThat(outbox.enqueued).isEmpty();
    }

    @Test void produceFailEnqueueFailed() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        outbox.failNext = true;
        var thrown = catchThrowable(() -> forwarder.forwardSync(ctx("agent-1", "m6")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        assertThat(((GovernanceException) thrown).code()).isEqualTo("ENQUEUE_FAILED");
    }

    @Test void correlationIdSelfGenerated() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m7"));
        assertThat(outbox.enqueued).hasSize(1);
        var env = outbox.enqueued.get(0);
        assertThat(env.correlationId()).startsWith("gw-correlation-");
        assertThat(env.correlationId()).doesNotContain("clientInvocationId");
    }

    @Test void envelopeTargetServiceIdFromRdc() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-target-9")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m8"));
        assertThat(outbox.enqueued.get(0).targetServiceId()).isEqualTo("svc-target-9");
    }

    @Test void noTopologyLeakInResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder.forwardSync(ctx("agent-1", "m9"));
        assertThat(resp.getBody()).doesNotContain("routeHandle", "endpoint", "topic", "worker");
    }

    @Test void g4CompleteAfterResponse() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        g4.check("T1", "m10", "fp"); // simulate governance pipeline registering IN_FLIGHT
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m10"));
        assertThat(g4.isCompleted("T1", "m10")).contains(true);
    }
}
