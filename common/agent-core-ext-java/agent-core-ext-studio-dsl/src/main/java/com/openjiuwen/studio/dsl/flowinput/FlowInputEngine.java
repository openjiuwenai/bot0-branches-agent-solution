/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowinput;

import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code FlowInput} — state machine START → USER_INTERACT → filled userFields.
 *
 * <p>START: write custom streams, store {@code flow_input_state}, then {@code session.interact}.
 * If interact raises {@link GraphInterrupt} / {@link WorkflowInteraction.GraphInterruptRuntimeWrapper},
 * it propagates (Python). If interact returns, invoke returns {@code {}} (Python {@code return {}}).
 * Resume returns {@code {userFields: filled}} only.
 *
 * @since 2026-08-26
 */
public final class FlowInputEngine {
    public static final String STATE_KEY = "flow_input_state";
    public static final String USER_FIELDS = FlowInputUtils.USER_FIELDS;
    public static final String JIUWEN_FLOW_INPUT_TYPE = "jiuwen.input";

    private final String nodeId;
    private final Map<String, Object> config;
    private final String nodeName;
    private FlowInputState nodeState = new FlowInputState();
    private Map<String, Object> streamOutput;

    public FlowInputEngine(String nodeId, Map<String, Object> config) {
        this.nodeId = nodeId;
        this.config = config == null ? Map.of() : deepCopyMap(config);
        Object name = this.config.get("name");
        this.nodeName = name == null || String.valueOf(name).isBlank() ? null : String.valueOf(name);
    }

    /**
     * Batch invoke — Python {@code FlowInput.invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @return output map (may wrap userFields)
     */
    public Map<String, Object> invoke(Object inputs, NodeSessionApi session) {
        try {
            Map<String, Object> inputMap = coerceInputs(inputs);
            FlowInputState fromSession = loadState(session);
            FlowInputState current =
                    fromSession.isUndergoingInteraction() ? fromSession : new FlowInputState();
            this.nodeState = current;

            if (FlowInputState.START.equals(current.status())) {
                return handleStart(inputMap, session, current);
            }
            if (FlowInputState.USER_INTERACT.equals(current.status())) {
                return handleInteract(inputMap, session, current);
            }
            Map<String, Object> passthrough = deepCopyMap(inputMap);
            return Map.of(USER_FIELDS, passthrough);
        } catch (NodeExecutionException e) {
            errorToOutput();
            throw e;
        } catch (RuntimeException e) {
            if (isGraphInterrupt(e)) {
                throw e;
            }
            errorToOutput();
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_FLOW_INPUT_TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "flow_input invoke failed",
                    e);
        }
    }

    /**
     * Stream — Python {@code FlowInput.stream}.
     *
     * @param inputs inputs
     * @param session session
     * @return frames
     */
    public Iterator<Object> stream(Object inputs, NodeSessionApi session) {
        List<Object> frames = new ArrayList<>();
        try {
            Map<String, Object> inputMap = coerceInputs(inputs);
            FlowInputState fromSession = loadState(session);
            FlowInputState current =
                    fromSession.isUndergoingInteraction() ? fromSession : new FlowInputState();
            this.nodeState = current;

            if (FlowInputState.START.equals(current.status())) {
                handleStart(inputMap, session, current);
                return frames.iterator();
            }
            if (FlowInputState.USER_INTERACT.equals(current.status())) {
                Map<String, Object> filled = handleInteract(inputMap, session, current);
                Object uf = filled.containsKey(USER_FIELDS) ? filled.get(USER_FIELDS) : filled;
                frames.add(Map.of("type", "complete", "index", 0, "payload", Map.of(USER_FIELDS, uf)));
                return frames.iterator();
            }
            frames.add(
                    Map.of(
                            "type",
                            "complete",
                            "index",
                            0,
                            "payload",
                            Map.of(USER_FIELDS, deepCopyMap(inputMap))));
            return frames.iterator();
        } catch (RuntimeException e) {
            if (isGraphInterrupt(e)) {
                throw e;
            }
            errorToOutput();
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_FLOW_INPUT_TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "flow_input stream failed",
                    e);
        }
    }

    public FlowInputState getState() {
        return nodeState;
    }

    public Map<String, Object> getStreamOutput() {
        return streamOutput;
    }

    public boolean shouldInterrupt() {
        return FlowInputState.USER_INTERACT.equals(nodeState.status());
    }

    private Map<String, Object> handleStart(
            Map<String, Object> inputs, NodeSessionApi session, FlowInputState current) {
        String query = FlowInputUtils.buildInputsMessage(config);
        current.setStatus(FlowInputState.USER_INTERACT);
        current.setQuestion(query);
        this.nodeState = current;
        storeState(session, current);

        Map<String, Object> custom = buildCustomData(session, query);
        writeStream(session, StudioStreamFrames.PARTIAL_CONTENT, 0, custom);
        writeStream(session, StudioStreamFrames.MESSAGE_NODE_END, 1, custom);
        this.streamOutput = custom;
        this.nodeState.setStatus(FlowInputState.END);

        sessionInteractOnly(session, query);
        // Python _handle_start_state: await session.interact(query); return {}
        // GraphInterrupt from interact propagates; otherwise empty map (engine/Nested detect
        // pause via flow_input_state == user_interact).
        return Map.of();
    }

    private Map<String, Object> handleInteract(
            Map<String, Object> inputs, NodeSessionApi session, FlowInputState current) {
        // Python: user_response = await session.interact(current_state.question)
        Object userResponse = sessionInteractOnly(session, current.question());
        if (userResponse == null) {
            // Java Nested / IR resume often puts answers in userFields or InteractiveInput
            // instead of session.interact — fall back only when interact returned nothing.
            userResponse = resolveReplyFromInputs(inputs);
        }
        Map<String, Object> values = FlowInputUtils.parseUserResponse(userResponse);
        return completeWithValues(inputs, values, session);
    }

    private Map<String, Object> completeWithValues(
            Map<String, Object> inputs, Map<String, Object> values, NodeSessionApi session) {
        Map<String, Object> inputsCopy = deepCopyMap(inputs);
        // Python fill/validate operate on the inputs dict (often already userFields contents).
        Map<String, Object> working = extractWorkingFields(inputsCopy);
        FlowInputUtils.fillInputs(working, values, config);
        try {
            FlowInputUtils.validateInputs(working, config);
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException(
                    nodeId, JIUWEN_FLOW_INPUT_TYPE, NodeCauseCode.NODE_INVOKE_FAILED, e.getMessage(), e);
        }

        FlowInputState fresh = new FlowInputState();
        this.nodeState = fresh;
        storeState(session, fresh);

        return Map.of(USER_FIELDS, working);
    }

    private Map<String, Object> buildCustomData(NodeSessionApi session, String message) {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("answer", message);
        custom.put("result", message);
        custom.put("node_id", nodeId);
        custom.put("node_name", nodeName != null ? nodeName : nodeId);
        custom.put("node_type", JIUWEN_FLOW_INPUT_TYPE);
        custom.put("should_interrupt", true);
        if (session != null) {
            try {
                Object parent = session.getClass().getMethod("getParentId").invoke(session);
                if (parent != null) {
                    custom.put("parentNodeId", parent);
                }
            } catch (ReflectiveOperationException ignored) {
                // optional
            }
        }
        return custom;
    }

    private void errorToOutput() {
        nodeState.setStatus(FlowInputState.END);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceInputs(Object inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        if (inputs instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (inputs instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return new LinkedHashMap<>(
                            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                    .build()
                                    .readValue(trimmed, Map.class));
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> extractWorkingFields(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return deepCopyMap(inputs);
    }

    /**
     * Python {@code await session.interact(...)} only — no userLatestInput short-circuit.
     */
    private static Object sessionInteractOnly(NodeSessionApi session, String question) {
        if (session == null) {
            return null;
        }
        try {
            return session.interact(question);
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            throw e;
        }
    }

    /** Resume answers carried on inputs (Java Nested InteractiveInput / IR), not Python. */
    private static Object resolveReplyFromInputs(Map<String, Object> inputs) {
        Map<String, Object> uf = extractWorkingFields(inputs);
        if (uf.containsKey("response")) {
            return uf.get("response");
        }
        if (inputs.containsKey("response")) {
            return inputs.get("response");
        }
        Object interactive = uf.getOrDefault("interactiveInput", inputs.get("interactiveInput"));
        Object fromIi = unwrapInteractive(interactive);
        if (fromIi != null) {
            return fromIi;
        }
        if (interactive != null && !(interactive instanceof InteractiveInput)) {
            return interactive;
        }
        return uf;
    }

    private static Object unwrapInteractive(Object interactive) {
        if (!(interactive instanceof InteractiveInput ii)) {
            return null;
        }
        if (ii.getRawInputs() != null) {
            return ii.getRawInputs();
        }
        if (ii.getUserInputs() != null && !ii.getUserInputs().isEmpty()) {
            Object last = ii.getUserInputs().values().stream().reduce((a, b) -> b).orElse(null);
            if (last instanceof Map<?, ?> m) {
                Object answer = m.get("answer");
                return answer != null ? answer : last;
            }
            return last;
        }
        return null;
    }

    private static void rethrowGraphInterrupt(RuntimeException e) {
        if (isGraphInterrupt(e)) {
            throw e;
        }
    }

    private static boolean isGraphInterrupt(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof GraphInterrupt
                    || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static FlowInputState loadState(NodeSessionApi session) {
        if (session == null) {
            return new FlowInputState();
        }
        try {
            Object raw = session.getState(STATE_KEY);
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return FlowInputState.deserialize(out);
            }
            Object state = session.getState(null);
            if (state instanceof Map<?, ?> all) {
                Object nested = all.get(STATE_KEY);
                if (nested instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return FlowInputState.deserialize(out);
                }
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return new FlowInputState();
    }

    private static void storeState(NodeSessionApi session, FlowInputState state) {
        if (session == null) {
            return;
        }
        try {
            session.updateState(Map.of(STATE_KEY, state.serialize()));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeStream(
            NodeSessionApi session, String type, int index, Map<String, Object> data) {
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.put("index", index);
            frame.put("data", data);
            session.writeCustomStream(frame);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        if (src == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> nested = new LinkedHashMap<>();
                m.forEach((nk, nv) -> nested.put(String.valueOf(nk), nv));
                out.put(k, nested);
            } else if (v instanceof List<?> l) {
                out.put(k, new ArrayList<>(l));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
