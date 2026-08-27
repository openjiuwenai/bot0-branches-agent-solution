/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import com.openjiuwen.core.session.NodeSessionApi;

import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot / restore session local state around nested sub-workflow execution (FEAT §5.5).
 * Interact / stream still forward to the parent session (needed for questioner / message).
 *
 * <p>Child interrupt keys ({@code questioner_state} / {@code flow_qa_state} /
 * {@code flow_input_state}) are re-seeded into the isolated frame and merged back after restore so
 * nested input/questioner can resume (Python {@code CHILD_INTERRUPT_STATE_KEYS}).
 *
 * @since 2026-08-17
 */

public final class SessionStateIsolator {

    /**
     * Align with Python {@code CHILD_INTERRUPT_STATE_KEYS}.
     */
    public static final Set<String> CHILD_INTERRUPT_STATE_KEYS =
            Set.of("questioner_state", "flow_qa_state", "flow_input_state");

    private SessionStateIsolator() {}

    /**
     * runIsolated.
     *
     * @param session session
     * @param body body
     * @return result
     */

    public static <T> T runIsolated(NodeSessionApi session, Supplier<T> body) {
        if (session == null) {
        return body.get();
    }
        Map<String, Object> snap = snapshot(session);
        try {
            clearLocal(session);
            reseedInterruptKeys(session, snap);
            return body.get();
        } finally {
            Map<String, Object> childInterrupt = copyInterruptKeys(session);
            restore(session, snap);
            if (!childInterrupt.isEmpty()) {
                try {
                    session.updateState(childInterrupt);
                } catch (IllegalStateException
                        | NullPointerException
                        | ClassCastException
                        | UnsupportedOperationException ignored) {
                    // mock / read-only session
                }
            }
        }
    }

    static Map<String, Object> snapshot(NodeSessionApi session) {
        try {
            Map<String, Object> dumped = session.dumpState();
            if (dumped == null) {
                return Map.of();
            }
            return DeepCopies.map(dumped);
        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException e) {
            return Map.of();
        }
    }

    static void clearLocal(NodeSessionApi session) {
        try {
            Map<String, Object> current = session.dumpState();
            if (current == null || current.isEmpty()) {
                return;
            }
            Map<String, Object> cleared = new LinkedHashMap<>();
            for (String k : current.keySet()) {
                cleared.put(k, null);
            }
            session.updateState(cleared);
        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
            // mock / read-only session
        }
    }

    static void restore(NodeSessionApi session, Map<String, Object> snap) {
        try {
            Map<String, Object> current = session.dumpState();
            Map<String, Object> patch = new LinkedHashMap<>();
            if (current != null) {
                for (String k : current.keySet()) {
                    if (!snap.containsKey(k)) {
                        patch.put(k, null);
                    }
                }
            }
            patch.putAll(snap);
            if (!patch.isEmpty()) {
                session.updateState(patch);
            }
        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
            // mock / read-only session
        }
    }

    static void reseedInterruptKeys(NodeSessionApi session, Map<String, Object> snap) {
        Map<String, Object> patch = copyInterruptKeysFrom(snap);
        if (patch.isEmpty()) {
            return;
        }
        try {
            session.updateState(patch);
        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
            // mock / read-only session
        }
    }

    static Map<String, Object> copyInterruptKeys(NodeSessionApi session) {
        try {
            Map<String, Object> current = session.dumpState();
            if (current == null) {
                return Map.of();
            }
            return copyInterruptKeysFrom(current);
        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException e) {
            return Map.of();
        }
    }

    static Map<String, Object> copyInterruptKeysFrom(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : CHILD_INTERRUPT_STATE_KEYS) {
            if (source.containsKey(key) && source.get(key) != null) {
                out.put(key, DeepCopies.value(source.get(key)));
            }
        }
        return out;
    }
}
