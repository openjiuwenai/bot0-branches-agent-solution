/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BusControlForwarder}.
 *
 * @since 2026-07-24
 */
class BusControlForwarderTest {
    private final InMemoryPayloadStore payloadStore = new InMemoryPayloadStore();
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final BusControlForwarder forwarder = new BusControlForwarder(
            new EnvelopeBuilder(), payloadStore, outbox);

    private static GovernanceContext ctx(String tenantId, String rawBody) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId(tenantId);
        c.setTraceId("trace-1");
        c.setMessageId("msg-1");
        c.setRawBody(rawBody);
        return c;
    }

    @Test
    void forwardEnqueuesEnvelope() {
        forwarder.forward(ctx("T1", "{\"a2a\":1}"), "handle-1", "svc-target", "svc-gw", 99999L);
        assertThat(outbox.enqueued()).hasSize(1);
        assertThat(outbox.enqueued().get(0).eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
        assertThat(outbox.enqueued().get(0).tenantId()).isEqualTo("T1");
    }

    @Test
    void forwardProduceUnavailableReturnsEnqueueFailed() {
        outbox.setFailNext(true);
        Throwable thrown = catchThrowable(() ->
                forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("ENQUEUE_FAILED");
        }
    }

    @Test
    void forwardRouteNotFoundReturnsEnqueueFailed() {
        outbox.setFailNext(true);
        Throwable thrown = catchThrowable(() ->
                forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("ENQUEUE_FAILED");
        }
        assertThat(outbox.enqueued()).isEmpty();
    }

    @Test
    void forwardDoesNotCallRuntimeDirect() {
        forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L);
        assertThat(outbox.enqueued()).hasSize(1);
    }

    @Test
    void forwardQueryWithoutMessageIdEnqueuesQueryEnvelope() {
        // GetTask queries carry no client message id — forwardQuery must synthesize an idempotency
        // key rather than NPE ("idempotencyKey is required") in the ForwardingEnvelope constructor.
        GovernanceContext queryCtx = new GovernanceContext();
        queryCtx.setTenantId("T1");
        queryCtx.setTraceId("trace-1");
        queryCtx.setRawBody("{\"jsonrpc\":\"2.0\",\"method\":\"GetTask\",\"params\":{\"id\":\"task-1\"}}");

        forwarder.forwardQuery(queryCtx, "handle-1", "svc-target", "svc-gw", 99999L);

        assertThat(outbox.enqueued()).hasSize(1);
        assertThat(outbox.enqueued().get(0).eventType())
                .isEqualTo(AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED);
        assertThat(outbox.enqueued().get(0).idempotencyKey()).isNotBlank();
    }

    @Test
    void forwardSubscribeWithoutMessageIdEnqueuesSubscribeEnvelope() {
        // SubscribeToTask likewise carries no client message id.
        GovernanceContext subscribeCtx = new GovernanceContext();
        subscribeCtx.setTenantId("T1");
        subscribeCtx.setTraceId("trace-1");
        subscribeCtx.setRawBody("{\"jsonrpc\":\"2.0\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"task-1\"}}");

        forwarder.forwardSubscribe(subscribeCtx, "handle-1", "svc-target", "svc-gw", 99999L);

        assertThat(outbox.enqueued()).hasSize(1);
        assertThat(outbox.enqueued().get(0).eventType())
                .isEqualTo(AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED);
        assertThat(outbox.enqueued().get(0).idempotencyKey()).isNotBlank();
    }
}
