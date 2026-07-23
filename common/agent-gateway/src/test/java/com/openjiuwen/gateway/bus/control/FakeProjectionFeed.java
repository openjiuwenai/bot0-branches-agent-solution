package com.openjiuwen.gateway.bus.control;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

/** Test double: FIFO queue (ignores correlationId filtering for test simplicity). */
public class FakeProjectionFeed implements ProjectionFeed {
    private final Queue<ProjectionEvent> queue = new LinkedList<>();

    public void inject(AgentBusEventType type, String taskId, String streamRef) {
        queue.add(new ProjectionEvent(type, taskId, streamRef, null));
    }

    @Override
    public Optional<ProjectionEvent> poll(String correlationId) {
        return Optional.ofNullable(queue.poll());
    }
}
