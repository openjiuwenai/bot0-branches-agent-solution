/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parent/child {@code _REQUEST} scope isolation + selective sync
 * (Python {@code SubWorkflow._enter_sub_request_scope} / {@code _exit_sub_request_scope}).
 *
 * <p>Enter: snapshot parent {@code _request}, replace global with {@code parent ∪ child}.
 * Exit: restore parent keys, overlay only keys that existed on the parent snapshot
 * (child-only temp keys are dropped).
 *
 * @since 2026-08-26
 */
public final class SubRequestScope {
    /** Python {@code REQUEST_VARIABLES}. */
    public static final String REQUEST_KEY = "_request";
    /** Alternate key used by Start / SetVariable. */
    public static final String REQUEST_KEY_ALT = "_REQUEST";

    private SubRequestScope() {}

    /**
     * Snapshot parent REQUEST and install {@code parent ∪ childRequest} into session global state.
     *
     * @param session session
     * @param childInputs child invoke/stream inputs (may contain {@code _REQUEST})
     * @return parent snapshot (deep copy)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> enter(NodeSessionApi session, Map<String, Object> childInputs) {
        Map<String, Object> parentSnapshot = DeepCopies.map(readRequest(session));
        Map<String, Object> childRequest = extractChildRequest(childInputs);
        Map<String, Object> merged = new LinkedHashMap<>(parentSnapshot);
        merged.putAll(childRequest);
        writeRequest(session, merged);
        return parentSnapshot;
    }

    /**
     * Restore parent REQUEST, syncing only keys that were declared on the parent snapshot.
     *
     * @param session session
     * @param parentSnapshot snapshot from {@link #enter}
     */
    public static void exit(NodeSessionApi session, Map<String, Object> parentSnapshot) {
        if (parentSnapshot == null) {
            return;
        }
        try {
            Map<String, Object> sub = readRequest(session);
            Map<String, Object> updated = new LinkedHashMap<>();
            for (String k : parentSnapshot.keySet()) {
                if (sub.containsKey(k)) {
                    updated.put(k, sub.get(k));
                }
            }
            Map<String, Object> restored = new LinkedHashMap<>(parentSnapshot);
            restored.putAll(updated);
            writeRequest(session, restored);
        } catch (RuntimeException e) {
            try {
                writeRequest(session, parentSnapshot);
            } catch (RuntimeException ignored) {
                // soft-fail like Python
            }
        }
    }

    /**
     * Build child {@code _REQUEST} view from inputs / userFields / session (Python
     * {@code _prepare_child_inputs} globals).
     */
    public static Map<String, Object> extractChildRequest(Map<String, Object> childInputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (childInputs == null) {
            return out;
        }
        Object req = childInputs.get(REQUEST_KEY_ALT);
        if (!(req instanceof Map<?, ?>)) {
            req = childInputs.get(REQUEST_KEY);
        }
        if (!(req instanceof Map<?, ?>)) {
            req = childInputs.get("global_variables");
        }
        if (!(req instanceof Map<?, ?>)) {
            Object uf = childInputs.get("userFields");
            if (uf instanceof Map<?, ?> um) {
                Object nested = um.get(REQUEST_KEY_ALT);
                if (!(nested instanceof Map<?, ?>)) {
                    nested = um.get(REQUEST_KEY);
                }
                if (nested instanceof Map<?, ?>) {
                    req = nested;
                }
            }
        }
        if (req instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    public static Map<String, Object> readRequest(NodeSessionApi session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (session == null) {
            return out;
        }
        try {
            Object raw = session.getGlobalState(REQUEST_KEY);
            if (!(raw instanceof Map<?, ?>)) {
                raw = session.getGlobalState(REQUEST_KEY_ALT);
            }
            if (!(raw instanceof Map<?, ?>)) {
                raw = session.getGlobalState("REQUEST_VARIABLES");
            }
            if (raw instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return out;
    }

    public static void writeRequest(NodeSessionApi session, Map<String, Object> request) {
        if (session == null) {
            return;
        }
        Map<String, Object> copy = request == null ? Map.of() : DeepCopies.map(request);
        try {
            session.updateGlobalState(Map.of(REQUEST_KEY, copy, REQUEST_KEY_ALT, copy));
        } catch (RuntimeException ignored) {
            // mock
        }
    }
}
