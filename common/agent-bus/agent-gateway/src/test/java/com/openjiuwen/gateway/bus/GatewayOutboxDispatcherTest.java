/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.gateway.bus.control.FakeBrokerForwardingProducerPort;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link GatewayOutboxDispatcher}: claim own records → produce → ack; skip
 * non-own (relay) records; retry on produce-unavailable.
 *
 * @since 2026-07-28
 */
class GatewayOutboxDispatcherTest {
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final FakeBrokerForwardingProducerPort producer = new FakeBrokerForwardingProducerPort();

    // claimPort + outbox are the same Fake (mirrors JdbcForwardingOutbox implementing both)
    private final GatewayOutboxDispatcher dispatcher = new GatewayOutboxDispatcher(
            outbox, producer, outbox, "tenant-a", "gateway-01", 60_000L, 1_000L, 10);

    private static ForwardingOutboxRecord rec(String source) {
        return new ForwardingOutboxRecord("tenant-a", new ForwardingMessageId("gw-" + source),
                source, "agent-bus-consumer-demo", new ForwardingRouteHandle("rh", "tenant-a"),
                null, ForwardingStatus.Outbox.PENDING, 0, 0, 0, 0, null, null,
                "corr-1", AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    @Test
    void dispatchOnceProducesAndAcksOwnRecord() {
        outbox.setClaimable(List.of(rec("gateway-01")));
        producer.setOutcome(BrokerProduceOutcome.accepted());
        int produced = dispatcher.dispatchOnce(1_000L);
        assertThat(produced).isEqualTo(1);
        assertThat(producer.produced()).hasSize(1);
        assertThat(outbox.acked()).hasSize(1);
        assertThat(outbox.released()).isEmpty();
    }

    @Test
    void dispatchOnceSkipsNonOwnRecordWithoutProducing() {
        // a relay hop2 record (source=eventbus-01) in the shared outbox must not be claimed/produced.
        // Source-scoped claimDue (source=gateway-01) never returns it, so nothing is claimed, produced,
        // acked, or released — the cross-role release branch is gone (defect A: no stranded residue).
        outbox.setClaimable(List.of(rec("eventbus-01")));
        int produced = dispatcher.dispatchOnce(1_000L);
        assertThat(produced).isEqualTo(0);
        assertThat(producer.produced()).isEmpty();
        assertThat(outbox.released()).isEmpty();
        assertThat(outbox.acked()).isEmpty();
    }

    @Test
    void dispatchOnceSchedulesRetryOnUnavailable() {
        outbox.setClaimable(List.of(rec("gateway-01")));
        producer.setOutcome(BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE));
        int produced = dispatcher.dispatchOnce(1_000L);
        assertThat(produced).isEqualTo(0);
        assertThat(outbox.retryScheduled()).hasSize(1);
        assertThat(outbox.acked()).isEmpty();
    }
}
