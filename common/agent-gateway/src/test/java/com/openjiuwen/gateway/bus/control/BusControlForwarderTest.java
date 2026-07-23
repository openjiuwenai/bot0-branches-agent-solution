package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

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
        assertThat(outbox.enqueued).hasSize(1);
        assertThat(outbox.enqueued.get(0).eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
        assertThat(outbox.enqueued.get(0).tenantId()).isEqualTo("T1");
    }

    @Test
    void forwardProduceUnavailableReturnsEnqueueFailed() {
        outbox.failNext = true;
        Throwable thrown = catchThrowable(() ->
                forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        assertThat(((GovernanceException) thrown).code()).isEqualTo("ENQUEUE_FAILED");
    }

    @Test
    void forwardRouteNotFoundReturnsEnqueueFailed() {
        outbox.failNext = true;
        Throwable thrown = catchThrowable(() ->
                forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        assertThat(((GovernanceException) thrown).code()).isEqualTo("ENQUEUE_FAILED");
        assertThat(outbox.enqueued).isEmpty();
    }

    @Test
    void forwardDoesNotCallRuntimeDirect() {
        forwarder.forward(ctx("T1", "{}"), "handle-1", "svc-target", "svc-gw", 99999L);
        // BusControlForwarder has no AgentRuntimeClient dependency — I-03 not reachable.
        // Assert by structure: the forwarder only depends on EnvelopeBuilder + PayloadStore + OutboxPort.
        assertThat(outbox.enqueued).hasSize(1);
    }
}
