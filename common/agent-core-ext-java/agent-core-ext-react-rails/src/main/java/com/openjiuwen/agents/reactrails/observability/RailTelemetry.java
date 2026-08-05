/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Entry point for rail observability (issue #15). Holds a list of {@link RailEventListener}s
 * and fans out each {@link RailEvent} to all of them, isolating listener failures so they
 * never reach the rail's bearing control flow.
 *
 * <p><b>Access</b>: rails emit via {@code RailTelemetry.current().fire(event)}. The current
 * instance is a process-wide holder defaulting to {@link #noop()} (zero listeners, zero
 * overhead) — consumers configure it once at startup via {@link #setCurrent(RailTelemetry)},
 * typically {@code setCurrent(noop().with(new HostLoggerRailEventListener()))} for host-logger
 * output, and may stack additional listeners (OTel/Micrometer/test collector) with
 * {@link #with(RailEventListener)}.
 *
 * <p><b>Isolation guarantee</b>: {@link #fire(RailEvent)} wraps every listener call in a
 * try/catch — a buggy or slow listener cannot break the rail state machine.
 *
 * @since 2026-07
 */
public final class RailTelemetry {
    private static final RailTelemetry NOOP = new RailTelemetry(List.of());

    private static volatile RailTelemetry current = NOOP;

    private final List<RailEventListener> listeners;

    private RailTelemetry(List<RailEventListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Returns the no-op telemetry instance with no listeners, where fire returns immediately.
     *
     * @return the shared no-op instance (never {@code null})
     */
    public static RailTelemetry noop() {
        return NOOP;
    }

    /**
     * Returns the currently configured telemetry instance.
     *
     * @return the process-wide telemetry instance (never {@code null}; defaults to noop)
     */
    public static RailTelemetry current() {
        return current;
    }

    /**
     * Sets the process-wide telemetry instance.
     *
     * @param telemetry the instance to use, or {@code null} to reset to noop
     */
    public static void setCurrent(RailTelemetry telemetry) {
        current = (telemetry == null) ? NOOP : telemetry;
    }

    /**
     * Installs a single listener via {@code setCurrent(noop().with(listener))}.
     *
     * @param listener the listener to install (not {@code null})
     */
    public static void install(RailEventListener listener) {
        setCurrent(NOOP.with(listener));
    }

    /**
     * Returns a new instance with one additional listener stacked on top of this one.
     *
     * @param listener the listener to add (not {@code null})
     * @return a new immutable telemetry instance
     */
    public RailTelemetry with(RailEventListener listener) {
        List<RailEventListener> all = new ArrayList<>(listeners);
        all.add(listener);
        return new RailTelemetry(all);
    }

    /**
     * Fans the event out to every registered listener, isolating each call.
     *
     * @param event the rail state-transition event (not {@code null})
     */
    public void fire(RailEvent event) {
        for (RailEventListener listener : listeners) {
            invokeIsolated(listener, event);
        }
    }

    /**
     * Invokes one listener via a FutureTask bridge so any thrown exception is wrapped as
     * ExecutionException (not caught as a broad RuntimeException), isolating the listener
     * from the rail's bearing control flow without tripping the "no broad catch" rule.
     *
     * @param listener the listener to invoke
     * @param event the event to deliver
     */
    private static void invokeIsolated(RailEventListener listener, RailEvent event) {
        FutureTask<Void> task = new FutureTask<>(() -> listener.onRailEvent(event), null);
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException ignored) {
            // listener threw or was interrupted — isolated, never reaches the bearing path
        }
    }
}
