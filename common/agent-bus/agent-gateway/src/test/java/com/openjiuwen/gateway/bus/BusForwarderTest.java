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
import com.openjiuwen.gateway.governance.ErrorCodes;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.MethodResultException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
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
            feed, g4, "svc-gw", 30_000L, 60_000L, null, sticky);

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
    void searchFailureReturnsRdcUnavailable() {
        // L2-014: RDC search-stage failure (network/5xx+cache-empty/4xx-non-404) → RDC_UNAVAILABLE,
        // distinct from ROUTE_NO_CANDIDATES (business-empty). BUS path (BusForwarder), not just DIRECT (Router).
        rdc.setSearchFails(true);
        var thrown = catchThrowable(() -> forwarder.forwardSync(ctx("agent-1", "m-rdc")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("RDC_UNAVAILABLE");
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
    void syncCreateBindsStickyOnAcceptedWithTask() {
        // P-13: BUS create must bind taskId -> routeHandle on a taskId-bearing projection (mirrors
        // DIRECT Router.routeCreate, which writes sticky from the response taskId), so a later resume
        // re-routes to the owning runtime instead of 404 RESUME_OWNER_UNKNOWN.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-7", null);
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardSync(ctx("agent-1", "m-sticky"));
        assertThat(sticky.find("T1", "task-7")).contains("h1");
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
        assertThat(sticky.find("T1", "ti-1")).contains("h1");
    }

    private GovernanceContext queryCtx(String taskId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setTaskId(taskId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"id\":\"" + taskId + "\"}}");
        return c;
    }

    @Test
    void forwardQueryStickyMissReturnsTaskNotFound() {
        // S6-2 (BUS): a GetTask for a taskId with no sticky owner → TASK_NOT_FOUND (§8.1 #8 —
        // cross-tenant / unknown → TaskNotFound at the Gateway layer, mirroring DIRECT routeGet).
        // Must NOT fall back to default-agent RDC + bus-forward to a random runtime (which returns
        // FAILED/TASK_NOT_FOUND and is multi-runtime-incorrect).
        rdc.setCandidates(List.of(new AgentCardRoute("h-default", "svc-default")));
        forwarder.setSingleResponseWindowMillis(50L); // keep the old (bus-forward) poll fast while RED
        Throwable thrown = catchThrowable(() -> forwarder.forwardQuery(queryCtx("ghost")));
        assertThat(thrown).isInstanceOf(MethodResultException.class);
        if (thrown instanceof MethodResultException mre) {
            assertThat(mre.errorCode()).isEqualTo(ErrorCodes.TASK_NOT_FOUND);
        }
        assertThat(outbox.enqueued()).isEmpty(); // sticky checked before enqueue — nothing forwarded
    }

    @Test
    void forwardQueryStickyHitForwardsToOwnerRuntime() {
        // S6-1 (BUS): a GetTask for a known taskId routes the query to the STICKY OWNER runtime
        // (routeHandle + targetServiceId bound at create), not a default-agent runtime picked from RDC.
        sticky.put("T1", "task-7", "h-owner", "svc-owner");
        rdc.setCandidates(List.of(new AgentCardRoute("h-default", "svc-default"))); // old path would pick this
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder.forwardQuery(queryCtx("task-7"));
        assertThat(outbox.enqueued()).hasSize(1);
        assertThat(outbox.enqueued().get(0).targetServiceId()).isEqualTo("svc-owner");
    }

    @Test
    void forwardSubscribeStickyMissReturnsTaskNotFound() {
        // S8-3 (BUS): a SubscribeToTask for a taskId with no sticky owner → TASK_NOT_FOUND (§8.1 #8
        // — cross-tenant / unknown → TaskNotFound at the Gateway layer, mirroring DIRECT
        // routeSubscribe + forwardQuery). Must NOT default-agent-bus-forward to a random runtime
        // (which times out → STREAM_NOT_AVAILABLE, multi-runtime-incorrect).
        rdc.setCandidates(List.of(new AgentCardRoute("h-default", "svc-default")));
        forwarder.setSingleResponseWindowMillis(50L);
        Throwable thrown = catchThrowable(() ->
                forwarder.forwardSubscribe(queryCtx("ghost"), null, new com.openjiuwen.gateway.sse.SseBridge()));
        assertThat(thrown).isInstanceOf(MethodResultException.class);
        if (thrown instanceof MethodResultException mre) {
            assertThat(mre.errorCode()).isEqualTo(ErrorCodes.TASK_NOT_FOUND);
        }
        assertThat(outbox.enqueued()).isEmpty();
    }
}
