/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.util.DeepCopies;
import com.openjiuwen.studio.dsl.util.SessionStateIsolator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SubWorkflow InteractiveInput resume helpers
 * (Python {@code SubWorkflow._prepare_child_inputs} / {@code _build_child_interactive_input}).
 *
 * @since 2026-08-26
 */

final class SubInteractiveSupport {
    private SubInteractiveSupport() {}

    static boolean shouldResume(NodeSessionApi session, Object inputs) {
        if (inputs instanceof InteractiveInput) {
        return true;
    }
        if (NestedWorkflowNodeHandler.detectInterruptInSession(session)) {
            return true;
        }
        if (inputs instanceof Map<?, ?> m) {
            Map<String, Object> uf = userFields(m);
            if (NestedWorkflowNodeHandler.isChildInterrupt(uf)) {
                return true;
            }
            if (Boolean.TRUE.equals(uf.get("__single_debug_recovery__"))) {
                return true;
            }
            if (m.get("interactiveInput") instanceof InteractiveInput
                    || uf.get("interactiveInput") instanceof InteractiveInput) {
                return true;
            }
        }
        return extractParentResumeQuery(session, inputs) != null;
    }

    /**
     * * Build child payload: InteractiveInput on resume (raw_inputs routing, same as Python), else dict.
     *
     * @param preparedDict preparedDict
     * @param session session
     * @param originalInputs originalInputs
     * @return result
     * @since 0.1.0
     */

    static Object prepareChildPayload(Map<String, Object> preparedDict, NodeSessionApi session, Object originalInputs) {
        if (!shouldResume(session, originalInputs) && !shouldResume(session, preparedDict)) {
        return preparedDict;
    }
        String query = extractParentResumeQuery(session, originalInputs);
        if (query == null) {
            query = extractParentResumeQuery(session, preparedDict);
        }
        if (query == null) {
            query = extractFromInteractiveInput(originalInputs);
        }
        if (query == null) {
            query = extractFromInteractiveInput(preparedDict.get("interactiveInput"));
        }
        if (query == null) {
            Map<String, Object> uf = userFieldsOfMap(preparedDict);
            Object q = firstPresent(uf, "query", "response", "userReply", "USER_RESPONSE", "name", "input");
            if (q != null) {
                query = String.valueOf(q);
            }
        }
        if (query == null || query.isBlank()) {
            // still signal resume so checkpointer / Input status path can run
            Map<String, Object> uf = userFieldsOfMap(preparedDict);
            if (!uf.isEmpty()) {
                InteractiveInput ii = new InteractiveInput();
                // prefer node-targeted updates when hang carried a node id
                Object nodeId = uf.get("node_id");
                if (nodeId != null && uf.get("response") != null) {
                    ii.update(String.valueOf(nodeId), uf.get("response"));
                } else {
                    return mergeInteractiveHint(preparedDict, null);
                }
                return mergeInteractiveHint(preparedDict, ii);
            }
            return preparedDict;
        }
        return mergeInteractiveHint(preparedDict, new InteractiveInput(query));
    }

    /**
     * * Linear Studio path cannot feed InteractiveInput into {@code asMap}; unwrap into userFields.
     *
     * @param childPayload childPayload
     * @param preparedDict preparedDict
     * @return result
     * @since 0.1.0
     */

    static Map<String, Object> unwrapForLinear(Object childPayload, Map<String, Object> preparedDict) {
        if (!(childPayload instanceof InteractiveInput ii)) {
            if (childPayload instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                return clearSoftHangMarkers(copy);
            }
            return clearSoftHangMarkers(DeepCopies.map(preparedDict));
        }
        Map<String, Object> out = DeepCopies.map(preparedDict);
        Map<String, Object> uf = new LinkedHashMap<>(userFieldsOfMap(out));
        String extracted = extractFromInteractiveInput(ii);
        if (extracted != null) {
            uf.putIfAbsent("query", extracted);
            uf.putIfAbsent("response", extracted);
            uf.putIfAbsent("USER_RESPONSE", extracted);
            uf.putIfAbsent("userReply", extracted);
            uf.put("interactiveInput", extracted);
        }
        // Clear soft-hang markers so parent resume does not immediately re-interrupt
        clearSoftHangMarkersInPlace(uf);
        if (ii.getUserInputs() != null) {
            ii.getUserInputs().forEach((k, v) -> {
                if (v instanceof Map<?, ?> vm) {
                    vm.forEach((kk, vv) -> uf.putIfAbsent(String.valueOf(kk), vv));
                } else if (v != null) {
                    uf.putIfAbsent(String.valueOf(k), v);
                }
            });
        }
        out.put("userFields", uf);
        out.put("interactiveInput", ii);
        return out;
    }

    private static Map<String, Object> clearSoftHangMarkers(Map<String, Object> envelope) {
        Map<String, Object> out = DeepCopies.map(envelope);
        Map<String, Object> uf = new LinkedHashMap<>(userFieldsOfMap(out));
        clearSoftHangMarkersInPlace(uf);
        out.put("userFields", uf);
        return out;
    }

    private static void clearSoftHangMarkersInPlace(Map<String, Object> uf) {
        uf.remove("should_interrupt");
        uf.remove("hangState");
        uf.remove("nestedWorkflowState");
        uf.remove("flowInputState");
        uf.remove("questionerState");
        uf.remove("inputReceived");
    }

    static String extractFromInteractiveInput(Object inputs) {
        if (!(inputs instanceof InteractiveInput ii)) {
        return null;
    }
        if (ii.getUserInputs() != null && !ii.getUserInputs().isEmpty()) {
            List<String> keys = List.copyOf(ii.getUserInputs().keySet());
            Object val = ii.getUserInputs().get(keys.get(keys.size() - 1));
            if (val instanceof Map<?, ?> m) {
                Object answer = m.get("answer");
                if (answer == null) {
                    answer = m.get("response");
                }
                if (answer != null) {
                    return String.valueOf(answer);
                }
            }
            if (val != null && !String.valueOf(val).isBlank()) {
                return String.valueOf(val);
            }
        }
        if (ii.getRawInputs() != null) {
            return String.valueOf(ii.getRawInputs());
        }
        return null;
    }

    static String extractParentResumeQuery(NodeSessionApi session, Object inputs) {
        String fromInput = extractFromInteractiveInput(inputs);
        if (fromInput != null) {
            return fromInput;
        }
        if (inputs instanceof Map<?, ?> m) {
            Object nested = m.get("interactiveInput");
            fromInput = extractFromInteractiveInput(nested);
            if (fromInput != null) {
                return fromInput;
            }
            Map<String, Object> uf = userFields(m);
            Object q = firstPresent(uf, "query", "response", "userReply", "USER_RESPONSE");
            // only treat as resume query when interrupt keys / recovery flag present
            if (q != null
                    && (Boolean.TRUE.equals(uf.get("__single_debug_recovery__"))
                            || NestedWorkflowNodeHandler.isChildInterrupt(uf)
                            || NestedWorkflowNodeHandler.detectInterruptInSession(session))) {
                return String.valueOf(q);
            }
        }
        if (session == null) {
            return null;
        }
        try {
            Object wf = session.getState(Constant.INTERACTIVE_INPUT);
            String normalized = normalizeInteractiveStored(wf);
            if (normalized != null) {
                return normalized;
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
        try {
            Map<String, Object> dumped = session.dumpState();
            if (dumped != null) {
                String nested = findInteractiveInTree(dumped, null);
                if (nested != null) {
                    return nested;
                }
                for (String key : SessionStateIsolator.CHILD_INTERRUPT_STATE_KEYS) {
                    Object st = dumped.get(key);
                    if (st instanceof Map<?, ?>) {
                        // interrupt metadata only — reply comes from InteractiveInput / userFields
                    }
                }
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
        return null;
    }

    static String normalizeInteractiveStored(Object value) {
        if (value == null) {
        return null;
    }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            Object last = list.get(list.size() - 1);
            if (last == null) {
                return null;
            }
            if (last instanceof InteractiveInput ii) {
                return extractFromInteractiveInput(ii);
            }
            if (last instanceof Map<?, ?> m) {
                Object content = m.containsKey("content") ? m.get("content") : m.get("answer");
                if (content != null) {
                    return String.valueOf(content);
                }
            }
            return String.valueOf(last);
        }
        if (value instanceof InteractiveInput ii) {
            return extractFromInteractiveInput(ii);
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String findInteractiveInTree(Object root, String targetNodeId) {
        if (!(root instanceof Map<?, ?> map)) {
        return null;
    }
        if (targetNodeId != null) {
            Object target = map.get(targetNodeId);
            if (target instanceof Map<?, ?> tm && tm.containsKey(Constant.INTERACTIVE_INPUT)) {
                return normalizeInteractiveStored(tm.get(Constant.INTERACTIVE_INPUT));
            }
        }
        if (map.containsKey(Constant.INTERACTIVE_INPUT)) {
            String n = normalizeInteractiveStored(map.get(Constant.INTERACTIVE_INPUT));
            if (n != null) {
                return n;
            }
        }
        for (Object v : map.values()) {
            if (v instanceof Map<?, ?>) {
                String nested = findInteractiveInTree(v, targetNodeId);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> mergeInteractiveHint(Map<String, Object> prepared, InteractiveInput ii) {
        Map<String, Object> out = DeepCopies.map(prepared);
        if (ii != null) {
            out.put("interactiveInput", ii);
            // core SubWorkflowComponent expects INPUTS_KEY to be the InteractiveInput itself
            out.put("__studio_child_inputs__", ii);
        }
        return out;
    }

    private static Map<String, Object> userFields(Map<?, ?> m) {
        Object uf = m.get("userFields");
        if (uf instanceof Map<?, ?> um) {
            Map<String, Object> out = new LinkedHashMap<>();
            um.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Map<String, Object> userFieldsOfMap(Map<String, Object> m) {
        return userFields(m);
    }

    private static Object firstPresent(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) {
        return m.get(k);
    }
        }
        return null;
    }
}
