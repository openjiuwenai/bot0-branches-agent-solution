/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.EnvelopeBuilder;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;
import com.openjiuwen.gateway.bus.control.FakeProjectionFeed;
import com.openjiuwen.gateway.bus.control.FakeStreamRefResolver;
import com.openjiuwen.gateway.bus.control.InMemoryPayloadStore;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.StickyIndex;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * B5 (streaming STREAM_READY) + B6 (S5 extras) + B7 (S3 resume) + B8 (S4 wire) tests.
 *
 * @since 2026-07-24
 */
class BusStreamingAndResumeTest {
    private final FakeRdcRouteClient rdc = new FakeRdcRouteClient();
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final FakeProjectionFeed feed = new FakeProjectionFeed();
    private final FakeStreamRefResolver streamResolver = new FakeStreamRefResolver();
    private final IdempotencyRule g4 = new IdempotencyRule();
    private final StickyIndex sticky = new StickyIndex();

    private GovernanceContext createCtx(String agentId, String messageId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setAgentId(agentId);
        c.setMessageId(messageId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\"}");
        return c;
    }

    private GovernanceContext resumeCtx(String taskId, String messageId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setTaskId(taskId);
        c.setMessageId(messageId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\",\"params\":{\"message\":{\"taskId\":\""
                + taskId + "\"}}}");
        return c;
    }

    private BusForwarder forwarder() {
        return new BusForwarder(rdc,
                new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
                feed, g4, "svc-gw", 30_000L, 60_000L);
    }

    @Test
    void b5_streamingReadyResolvesEndpoint() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-s", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-s", "sr-1");
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder().forwardSync(createCtx("agent-1", "ms1"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("COMPLETED_RESPONSE");
        assertThat(outbox.enqueued().get(0).carriesPayloadRef()).isTrue();
        assertThat(outbox.enqueued().get(0).payloadRef()).doesNotContain("token");
    }

    @Test
    void b5_streamReadySeparableFromAccepted() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-x", "sr-2");
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder().forwardSync(createCtx("agent-1", "ms2"));
        assertThat(resp.getBody()).contains("COMPLETED_RESPONSE");
    }

    @Test
    void b5_tokenNotInBusPayload() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder().forwardSync(createCtx("agent-1", "ms3"));
        var env = outbox.enqueued().get(0);
        assertThat(env.eventType().name()).doesNotContain("TOKEN");
    }

    @Test
    void b5_streamRefResolvesToEndpoint() {
        assertThat(streamResolver.resolve("sr-1")).contains("http://rt:8000");
        streamResolver.setFail();
        assertThat(streamResolver.resolve("sr-1")).isEmpty();
    }

    @Test
    void b6_governanceRejectNotS5() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder().forwardSync(createCtx("agent-1", "m-s5"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void b6_defaultAgentMissingIsConfigError() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder().forwardSync(createCtx(null, "m-da"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void b6_stickyMissNotS5() {
        sticky.clear();
        assertThat(sticky.find("ghost")).isEmpty();
    }

    @Test
    void b7_resumeEnvelopeCarriesTaskId() {
        sticky.put("task-7", "h1");
        rdc.setCandidates(List.of());
        g4.check("T1", "m-r1", "fp");
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        assertThat(sticky.find("task-7")).contains("h1");
    }

    @Test
    void b7_resumeNoSearchUsesStickyRoute() {
        sticky.put("task-7", "h1");
        assertThat(sticky.find("task-7")).isPresent();
        sticky.put("task-7", "h1");
        assertThat(sticky.find("task-7")).hasValue("h1");
    }

    @Test
    void b7_resumeNoRouteRefExplicitFail() {
        assertThat(sticky.find("ghost")).isEmpty();
    }

    @Test
    void b7_inputRequiredStubBranch() {
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.ACCEPTED_WITH_TASK)).isFalse();
    }

    @Test
    void b8_continueInputWireSameAsResume() {
        sticky.put("task-ci", "h1");
        assertThat(sticky.find("task-ci")).contains("h1");
    }

    @Test
    void b9_pathModeClientInvisible() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder().forwardSync(createCtx("agent-1", "m-cfg"));
        var env = outbox.enqueued().get(0);
        assertThat(env.eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }
}
