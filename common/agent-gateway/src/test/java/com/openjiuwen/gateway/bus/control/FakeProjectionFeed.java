/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

/**
 * Test double: FIFO queue (ignores correlationId filtering for test simplicity).
 *
 * @since 2026-07-24
 */
public class FakeProjectionFeed implements ProjectionFeed {
    private final Queue<ProjectionEvent> queue = new LinkedList<>();

    /**
     * Enqueues a synthetic projection event for tests.
     *
     * @param type bus event type
     * @param taskId optional task id
     * @param streamRef optional stream ref
     */
    public void inject(AgentBusEventType type, String taskId, String streamRef) {
        queue.add(new ProjectionEvent(type, taskId, streamRef, null));
    }

    @Override
    public Optional<ProjectionEvent> poll(String correlationId) {
        return Optional.ofNullable(queue.poll());
    }
}
