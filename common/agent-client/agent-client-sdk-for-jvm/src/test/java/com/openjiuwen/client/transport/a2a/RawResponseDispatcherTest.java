/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.transport.spi.RawResponseEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class RawResponseDispatcherTest {
    @Test
    void acceptedEventMarksObservationsDroppedBeforeIt() {
        List<RawResponseEvent> observed = new ArrayList<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        RawResponseDispatcher dispatcher = new RawResponseDispatcher(observed::add,
                scheduled::set, 1);
        try {
            dispatcher.offer(event("first"));
            dispatcher.offer(event("dropped"));
            scheduled.get().run();

            dispatcher.offer(event("second"));
            scheduled.get().run();

            assertEquals(List.of("first", "second"), observed.stream()
                    .map(RawResponseEvent::body).toList());
            assertEquals(0L, observed.get(0).droppedBefore());
            assertEquals(1L, observed.get(1).droppedBefore());
            assertEquals(1L, dispatcher.droppedCount());
        } finally {
            dispatcher.close();
        }
    }

    private static RawResponseEvent event(String body) {
        return new RawResponseEvent("inv", "conversation", "task", EndpointType.RUNTIME,
                RawResponseEvent.Source.CREATE_UNARY, 200, java.util.Map.of(), body,
                null, false, Instant.now(), 0L);
    }
}
