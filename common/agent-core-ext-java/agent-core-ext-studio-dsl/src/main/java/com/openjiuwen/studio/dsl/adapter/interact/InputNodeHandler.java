/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.input — FlowInput state machine (START → USER_INTERACT / INPUT_REQUIRED → filled fields).
 *
 * @since 2026-08-17
 */
public final class InputNodeHandler implements NodeHandlerFactory {
    static final String STATE_KEY = "flow_input_state";

    @Override
    public String canonicalType() {
        return "jiuwen.input";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowInput");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new InputExecutable(node);
    }

    static final class InputExecutable extends AbstractStudioNode {
        InputExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Map<String, Object> state = loadState(session);
            String status = String.valueOf(state.getOrDefault("status", "start"));

            if ("user_interact".equals(status)) {
                Object reply = resolveReply(inputs, uf, session, String.valueOf(state.getOrDefault("question", "")));
                Map<String, Object> values = FlowInputUtils.parseUserResponse(reply);
                Map<String, Object> filled = fillAndValidate(values, uf);
                storeState(session, Map.of("status", "start", "question", ""));
                filled.put("inputReceived", true);
                filled.put("flowInputState", "end");
                return NodePayload.userFields(filled);
            }

            // Fresh start: if required fields already present, complete without hang.
            if (requiredFieldsPresent(uf)) {
                Map<String, Object> filled = fillAndValidate(uf, uf);
                filled.put("inputReceived", true);
                filled.put("flowInputState", "end");
                return NodePayload.userFields(filled);
            }

            String question = buildInputsMessage();
            Map<String, Object> interactState = new LinkedHashMap<>();
            interactState.put("status", "user_interact");
            interactState.put("question", question);
            storeState(session, interactState);

            Map<String, Object> custom = new LinkedHashMap<>();
            custom.put("answer", question);
            custom.put("result", question);
            custom.put("node_id", node.id());
            custom.put("node_name", node.configs().getOrDefault("name", node.id()));
            custom.put("node_type", "jiuwen.input");
            custom.put("should_interrupt", true);
            StudioStreamFrames.emitPartialAndMessageEnd(session, custom);

            Object viaInteract = tryInteract(session, question);
            if (viaInteract != null) {
                Map<String, Object> values = FlowInputUtils.parseUserResponse(viaInteract);
                Map<String, Object> filled = fillAndValidate(values, uf);
                storeState(session, Map.of("status", "start", "question", ""));
                filled.put("inputReceived", true);
                filled.put("flowInputState", "end");
                return NodePayload.userFields(filled);
            }

            Map<String, Object> hang = new LinkedHashMap<>(uf);
            hang.put("inputReceived", false);
            hang.put("flowInputState", "user_interact");
            hang.put("question", question);
            hang.put("hangState", "INPUT_REQUIRED");
            hang.put("STATUS", "INPUT_REQUIRED");
            hang.put("should_interrupt", true);
            return NodePayload.userFields(hang);
        }

        private boolean requiredFieldsPresent(Map<String, Object> uf) {
            for (Map<String, Object> def : inputDefs()) {
                String id = fieldId(def);
                if (id == null) {
                    continue;
                }
                boolean required = Boolean.TRUE.equals(def.get("required")) || "true".equalsIgnoreCase(String.valueOf(def.get("required")));
                if (required) {
                    Object v = uf.get(id);
                    if (v == null || String.valueOf(v).isBlank()) {
                        return false;
                    }
                }
            }
            // If schema declares fields but none required, still interrupt unless any field present.
            List<Map<String, Object>> defs = inputDefs();
            if (defs.isEmpty()) {
                return !uf.isEmpty();
            }
            boolean anyDeclaredPresent = false;
            for (Map<String, Object> def : defs) {
                String id = fieldId(def);
                if (id != null && uf.containsKey(id) && uf.get(id) != null) {
                    anyDeclaredPresent = true;
                    break;
                }
            }
            return anyDeclaredPresent;
        }

        private String buildInputsMessage() {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Map<String, Object> def : inputDefs()) {
                Map<String, Object> c = new LinkedHashMap<>(def);
                if (c.containsKey("id") && !c.containsKey("name")) {
                    c.put("name", c.get("id"));
                }
                converted.add(c);
            }
            // compact JSON-like string (Python json.dumps); avoid extra JSON dep
            StringBuilder sb = new StringBuilder("{\"inputs\":[");
            for (int i = 0; i < converted.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(mapToJsonish(converted.get(i)));
            }
            sb.append("]}");
            return sb.toString();
        }

        private Map<String, Object> fillAndValidate(Map<String, Object> values, Map<String, Object> base) {
            Map<String, Object> out = new LinkedHashMap<>(base);
            List<Map<String, Object>> defs = inputDefs();
            if (defs.isEmpty()) {
                out.putAll(values);
                return out;
            }
            for (Map<String, Object> def : defs) {
                String id = fieldId(def);
                if (id == null) {
                    continue;
                }
                boolean required =
                        Boolean.TRUE.equals(def.get("required"))
                                || "true".equalsIgnoreCase(String.valueOf(def.get("required")));
                String type = String.valueOf(def.getOrDefault("type", "string")).toLowerCase();
                Object value = values.containsKey(id) ? values.get(id) : out.get(id);
                if (!required && (value == null || "".equals(value))) {
                    value = typeDefault(type);
                }
                if (required && (value == null || "".equals(value))) {
                    throw new NodeExecutionException(
                            node.id(),
                            node.canonicalType(),
                            NodeCauseCode.NODE_INVOKE_FAILED,
                            "Required field '" + id + "' is missing");
                }
                if (value != null && !"".equals(value)) {
                    value = coerce(id, value, type);
                }
                out.put(id, value);
            }
            return out;
        }

        private Object coerce(String id, Object value, String type) {
            try {
                return switch (type) {
                    case "number" -> value instanceof Number n ? n : Double.parseDouble(String.valueOf(value));
                    case "boolean" -> {
                        if (value instanceof Boolean b) {
                            yield b;
                        }
                        String s = String.valueOf(value).toLowerCase();
                        yield s.equals("true") || s.equals("1") || s.equals("yes");
                    }
                    case "array" -> {
                        if (value instanceof List<?> l) {
                            yield l;
                        }
                        yield List.of(String.valueOf(value));
                    }
                    case "object" -> {
                        if (value instanceof Map<?, ?> m) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                            yield copy;
                        }
                        yield Map.of("value", value);
                    }
                    default -> String.valueOf(value);
                };
            } catch (RuntimeException e) {
                throw new NodeExecutionException(
                        node.id(),
                        node.canonicalType(),
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "Field '" + id + "' has invalid type: " + e.getMessage(),
                        e);
            }
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> inputDefs() {
            Object fields = node.configs().get("fields");
            if (fields instanceof Map<?, ?> schema && !(schema.containsKey("inputs"))) {
                List<Map<String, Object>> list = new ArrayList<>();
                schema.forEach((k, v) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", String.valueOf(k));
                    d.put("required", false);
                    d.put("type", "string");
                    if (v instanceof Map<?, ?> m) {
                        m.forEach((kk, vv) -> d.put(String.valueOf(kk), vv));
                    }
                    list.add(d);
                });
                return list;
            }
            Object uf = node.configs().get("userFields");
            Object inputsCfg = null;
            if (uf instanceof Map<?, ?> m) {
                inputsCfg = m.get("inputs");
            }
            if (inputsCfg == null) {
                inputsCfg = node.configs().get("inputs");
            }
            if (inputsCfg instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        m.forEach((k, v) -> d.put(String.valueOf(k), v));
                        out.add(d);
                    }
                }
                return out;
            }
            return List.of();
        }

        private static String fieldId(Map<String, Object> def) {
            Object id = def.get("id");
            if (id == null) {
                id = def.get("name");
            }
            if (id == null) {
                id = def.get("fieldName");
            }
            return id == null ? null : String.valueOf(id);
        }

        private static Object typeDefault(String type) {
            return switch (type) {
                case "number" -> 0;
                case "boolean" -> false;
                case "array" -> List.of();
                case "object" -> Map.of();
                default -> "";
            };
        }

        private static Object resolveReply(
                Map<String, Object> inputs, Map<String, Object> uf, NodeSessionApi session, String question) {
            if (uf.containsKey("response")) {
                return uf.get("response");
            }
            if (inputs != null && inputs.containsKey("response")) {
                return inputs.get("response");
            }
            Object interactive = uf.getOrDefault("interactiveInput", inputs == null ? null : inputs.get("interactiveInput"));
            if (interactive != null) {
                return interactive;
            }
            if (session != null) {
                try {
                    Object latest = session.userLatestInput(question);
                    if (latest != null) {
                        return latest;
                    }
                } catch (RuntimeException ignored) {
                    // mock
                }
                try {
                    return session.interact(question);
                } catch (RuntimeException ignored) {
                    // mock
                }
            }
            return uf;
        }

        private static Object tryInteract(NodeSessionApi session, String question) {
            if (session == null) {
                return null;
            }
            try {
                Object latest = session.userLatestInput(question);
                if (latest != null) {
                    return latest;
                }
            } catch (RuntimeException ignored) {
                // mock
            }
            try {
                return session.interact(question);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> loadState(NodeSessionApi session) {
            if (session == null) {
                return Map.of("status", "start");
            }
            try {
                Object raw = session.getState(STATE_KEY);
                if (raw instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
                Object state = session.getState(null);
                if (state instanceof Map<?, ?> all) {
                    Object nested = all.get(STATE_KEY);
                    if (nested instanceof Map<?, ?> m) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        m.forEach((k, v) -> out.put(String.valueOf(k), v));
                        return out;
                    }
                }
            } catch (RuntimeException ignored) {
                // mock
            }
            return Map.of("status", "start");
        }

        private static void storeState(NodeSessionApi session, Map<String, Object> state) {
            if (session == null) {
                return;
            }
            try {
                session.updateState(Map.of(STATE_KEY, state));
            } catch (RuntimeException ignored) {
                // mock
            }
        }

        private static String mapToJsonish(Map<String, Object> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else if (v == null) {
                    sb.append("null");
                } else {
                    sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
                }
            }
            sb.append('}');
            return sb.toString();
        }
    }
}
