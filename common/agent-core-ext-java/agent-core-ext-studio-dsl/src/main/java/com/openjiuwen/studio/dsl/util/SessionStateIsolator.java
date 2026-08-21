package com.openjiuwen.studio.dsl.util;

import com.openjiuwen.core.session.NodeSessionApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Snapshot / restore session local state around nested sub-workflow execution (FEAT §5.5).
 * Interact / stream still forward to the parent session (needed for questioner / message).
 */
public final class SessionStateIsolator {
    private SessionStateIsolator() {}

    public static <T> T runIsolated(NodeSessionApi session, Supplier<T> body) {
        if (session == null) {
            return body.get();
        }
        Map<String, Object> snap = snapshot(session);
        try {
            clearLocal(session);
            return body.get();
        } finally {
            restore(session, snap);
        }
    }

    static Map<String, Object> snapshot(NodeSessionApi session) {
        try {
            Map<String, Object> dumped = session.dumpState();
            if (dumped == null) {
                return Map.of();
            }
            return DeepCopies.map(dumped);
        } catch (RuntimeException e) {
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
        } catch (RuntimeException ignored) {
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
        } catch (RuntimeException ignored) {
            // mock / read-only session
        }
    }
}
