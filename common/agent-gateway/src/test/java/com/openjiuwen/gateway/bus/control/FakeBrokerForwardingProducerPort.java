/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the SDK {@code requestProducer} ({@link BrokerForwardingProducerPort}):
 * records each produced record + returns a configurable outcome (default {@link BrokerProduceOutcome#accepted()}).
 *
 * @since 2026-07-28
 */
public class FakeBrokerForwardingProducerPort implements BrokerForwardingProducerPort {
    private BrokerProduceOutcome outcome = BrokerProduceOutcome.accepted();
    private final List<ForwardingOutboxRecord> produced = new ArrayList<>();

    /**
     * Returns records produced via {@link #produce(ForwardingOutboxRecord, long)}, in order.
     *
     * @return produced records
     */
    public List<ForwardingOutboxRecord> produced() {
        return produced;
    }

    /**
     * Overrides the outcome returned by the next produce calls.
     *
     * @param outcome the produce outcome
     */
    public void setOutcome(BrokerProduceOutcome outcome) {
        this.outcome = outcome;
    }

    @Override
    public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
        produced.add(record);
        return outcome;
    }
}
