/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only {@link RailEventListener} that collects every fired {@link RailEvent} into a
 * thread-safe list for mutation-RED assertions.
 *
 * <p><b>Honest scope</b>: this is a test spy, not production code. It only records events;
 * it never interprets them. The bearing control flow (forceFinish / pushSteering) lives in
 * the rail itself and is verified by the rail's own承重 tests — here we assert the IFF
 * binding "control-flow exit happened ↔ corresponding RailEvent was collected".
 *
 * <p>Thread-safe: rails may fire from different threads in real loops, and the isolation
 * test runs concurrent listeners.
 *
 * @since 2026-07
 */
public final class CollectingRailEventListener implements RailEventListener {
    private final List<RailEvent> events = new ArrayList<>();

    @Override
    public synchronized void onRailEvent(RailEvent event) {
        events.add(event);
    }

    /**
     * Snapshot of all collected events (copy, safe to assert against after the rail ran).
     *
     * @return unmodifiable copy of collected events in firing order
     */
    public synchronized List<RailEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Filters collected events by type.
     *
     * @param type the transition kind to keep
     * @return unmodifiable copy of events matching the type
     */
    public synchronized List<RailEvent> ofType(RailEventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    /**
     * Resets the collector (used when reusing one instance across scenarios).
     */
    public synchronized void clear() {
        events.clear();
    }
}
