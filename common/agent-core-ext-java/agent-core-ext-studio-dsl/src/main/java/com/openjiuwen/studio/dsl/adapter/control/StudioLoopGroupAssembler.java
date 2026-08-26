/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.WorkflowComponent;
import com.openjiuwen.core.workflow.component.loop.LoopBreakComponent;
import com.openjiuwen.core.workflow.component.loop.LoopGroup;
import com.openjiuwen.core.workflow.components.flow.loop.LoopComponent;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assemble Studio IR {@code loopBody} into core {@link LoopGroup} + {@link LoopComponent}
 * (same shape as Python {@code test_loop_component.py} / {@code LoopComponent + LoopGroup}).
 *
 * @since 2026-08-26
 */
final class StudioLoopGroupAssembler {
    private StudioLoopGroupAssembler() {}

    static AssembledLoop assemble(
            AssembledNode loopNode, NodeTypeRegistry registry, NodeBuildContext ctx, AtomicReference<Map<String, Object>> frame) {
        Map<String, Object> configs = loopNode.configs() == null ? Map.of() : loopNode.configs();
        List<AssembledNode> body = parseBody(configs.get("loopBody"));
        if (body.isEmpty()) {
            body = List.of();
        }

        LoopGroup group = new LoopGroup();
        List<String> ids = new ArrayList<>();
        String loopType = stringVal(configs.getOrDefault("loop_type", configs.get("loopType")));
        String arrayKey = resolveArrayKey(configs);
        for (AssembledNode child : body) {
            ids.add(child.id());
            Map<String, Object> inputsSchema = inputsSchemaOf(child);
            if (inputsSchema == null) {
                Map<String, Object> schema = new LinkedHashMap<>();
                // Same as Python: ${loop_node.index} / ${loop_node.item}
                schema.put("index", "${" + loopNode.id() + ".index}");
                if ("array".equalsIgnoreCase(loopType) && arrayKey != null) {
                    schema.put(arrayKey, "${" + loopNode.id() + "." + arrayKey + "}");
                }
                inputsSchema = schema;
            }
            group.addWorkflowComp(child.id(), new StudioLoopBodyAdapter(child, registry, ctx, frame), inputsSchema);
        }

        Object breakCond = configs.get("breakCondition");
        if (breakCond == null) {
            breakCond = configs.get("break_condition");
        }
        String breakId = null;
        if (breakCond != null) {
            breakId = "_studio_break";
            group.addWorkflowComp(breakId, new StudioLoopBreakAdapter(breakCond, frame));
        }
        final String breakNodeId = breakId;

        List<String> start = stringList(configs.getOrDefault("startNodes", configs.get("start_nodes")));
        List<String> end = stringList(configs.getOrDefault("endNodes", configs.get("end_nodes")));
        List<Conn> conns = parseConnections(configs.getOrDefault("connections", configs.get("loopConnections")));

        if (start.isEmpty() && !ids.isEmpty()) {
            start = List.of(ids.get(0));
        }
        if (end.isEmpty()) {
            if (breakNodeId != null) {
                end = List.of(breakNodeId);
            } else if (!ids.isEmpty()) {
                end = List.of(ids.get(ids.size() - 1));
            }
        }
        if (conns.isEmpty() && ids.size() > 1) {
            for (int i = 0; i < ids.size() - 1; i++) {
                conns.add(new Conn(ids.get(i), ids.get(i + 1)));
            }
        }
        if (breakNodeId != null && !ids.isEmpty()) {
            boolean linked = conns.stream().anyMatch(c -> breakNodeId.equals(c.to));
            if (!linked) {
                conns.add(new Conn(ids.get(ids.size() - 1), breakNodeId));
            }
        }

        if (!start.isEmpty()) {
            group.startNodes(start);
        }
        if (!end.isEmpty()) {
            group.endNodes(end);
        }
        for (Conn c : conns) {
            group.addConnection(c.from, c.to);
        }

        Map<String, Object> outputSchema = toCoreOutputSchema(configs, ids);
        LoopComponent loop = new LoopComponent(group, outputSchema);
        return new AssembledLoop(loop, outputSchema, ids, breakNodeId);
    }

    static Map<String, Object> toCoreOutputSchema(Map<String, Object> configs, List<String> bodyIds) {
        Object schema = configs.getOrDefault("output_schema", configs.get("outputSchema"));
        Map<String, Object> out = new LinkedHashMap<>();
        String defaultNode = bodyIds.isEmpty() ? "body" : bodyIds.get(bodyIds.size() - 1);
        String resultNode = defaultNode;
        for (String id : bodyIds) {
            if ("msg".equals(id) || "body".equals(id) || id.contains("message")) {
                resultNode = id;
                break;
            }
        }
        if (!(schema instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object raw = e.getValue();
            if (raw == null) {
                out.put(key, "${" + defaultNode + "." + key + "}");
                continue;
            }
            String v = String.valueOf(raw).trim();
            if (v.startsWith("${") && v.endsWith("}")) {
                out.put(key, v);
            } else if (v.contains(".")) {
                out.put(key, "${" + v + "}");
            } else if ("result".equals(v) || "answer".equals(v) || "text".equals(key)) {
                out.put(key, "${" + resultNode + "." + v + "}");
            } else {
                // cross-iteration fields (total, …) come from last body node (setVariable)
                out.put(key, "${" + defaultNode + "." + v + "}");
            }
        }
        return out;
    }

    private static String resolveArrayKey(Map<String, Object> configs) {
        Object loopArray = configs.getOrDefault("loop_array", configs.get("loopArray"));
        if (loopArray instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) {
                Object v = map.get(k);
                if (v instanceof List<?>) {
                    return String.valueOf(k);
                }
            }
        }
        if (loopArray instanceof List<?>) {
            return "item";
        }
        return "item";
    }

    static Map<String, Object> buildLoopInputs(Map<String, Object> configs, Map<String, Object> userFields) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        String loopType = stringVal(configs.getOrDefault("loop_type", configs.get("loopType")));
        if (loopType.isBlank()) {
            Object arr = configs.getOrDefault("loop_array", configs.get("loopArray"));
            loopType = arr != null ? "array" : "number";
        }
        inputs.put("loop_type", loopType.toLowerCase());

        Object loopNumber =
                configs.getOrDefault(
                        "loop_number",
                        configs.getOrDefault("loopNumber", configs.getOrDefault("maxIterations", configs.get("loopCount"))));
        if (loopNumber != null) {
            inputs.put("loop_number", loopNumber);
        }

        Object loopArray = configs.getOrDefault("loop_array", configs.get("loopArray"));
        if (loopArray instanceof List<?> list) {
            inputs.put("loop_array", Map.of("item", new ArrayList<>(list)));
        } else if (loopArray instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            inputs.put("loop_array", copy);
        }

        Object intermediate =
                configs.getOrDefault("intermediate_var", configs.get("intermediateVar"));
        if (intermediate instanceof Map<?, ?> im) {
            Map<String, Object> iv = new LinkedHashMap<>();
            im.forEach((k, v) -> iv.put(String.valueOf(k), v));
            // merge userFields seeds
            if (userFields != null) {
                for (String k : iv.keySet()) {
                    if (userFields.containsKey(k) && userFields.get(k) != null) {
                        iv.put(k, userFields.get(k));
                    }
                }
            }
            inputs.put("intermediate_var", iv);
        } else if (userFields != null && !userFields.isEmpty()) {
            // seed plain user fields that look like intermediate state
            Map<String, Object> iv = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : userFields.entrySet()) {
                if (!e.getKey().startsWith("__") && !(e.getValue() instanceof Map || e.getValue() instanceof List)) {
                    iv.put(e.getKey(), e.getValue());
                }
            }
            if (!iv.isEmpty()) {
                inputs.put("intermediate_var", iv);
            }
        }
        return inputs;
    }

    @SuppressWarnings("unchecked")
    static List<AssembledNode> parseBody(Object loopBody) {
        List<AssembledNode> out = new ArrayList<>();
        if (!(loopBody instanceof List<?> list)) {
            return out;
        }
        int i = 0;
        for (Object item : list) {
            if (item instanceof AssembledNode an) {
                out.add(an);
            } else if (item instanceof Map<?, ?> m) {
                Object idObj = m.get("id");
                String id = idObj == null ? "loop-body-" + i : String.valueOf(idObj);
                Object typeObj = m.get("type");
                if (typeObj == null) {
                    typeObj = m.get("irType");
                }
                String type = typeObj == null ? "" : String.valueOf(typeObj);
                if (type.isBlank() || "null".equals(type)) {
                    throw new NodeExecutionException(
                            id, "jiuwen.loop", NodeCauseCode.NODE_CONFIG_INVALID, "loopBody item missing type");
                }
                Object cfg = m.get("configs");
                Map<String, Object> configs = cfg instanceof Map<?, ?> cm ? cast(cm) : Map.of();
                // preserve optional inputs_schema on the map itself
                AssembledNode node = AssembledNode.of(id, type, configs);
                Object inSchema = m.containsKey("inputs_schema") ? m.get("inputs_schema") : m.get("inputsSchema");
                if (inSchema != null) {
                    Map<String, Object> enriched = new LinkedHashMap<>(configs);
                    enriched.put("_inputs_schema", inSchema);
                    node = AssembledNode.of(id, type, enriched);
                }
                out.add(node);
            }
            i++;
        }
        return out;
    }

    private static Map<String, Object> inputsSchemaOf(AssembledNode child) {
        Object raw = child.configs() == null ? null : child.configs().get("_inputs_schema");
        if (raw instanceof Map<?, ?> m) {
            return cast(m);
        }
        // default: no schema — graph passes resolved upstream outputs / intermediate via session
        return null;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    private static List<Conn> parseConnections(Object raw) {
        List<Conn> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object from = firstPresent(m, "from", "source", "src");
                Object to = firstPresent(m, "to", "target", "dst");
                if (from != null && to != null) {
                    out.add(new Conn(String.valueOf(from), String.valueOf(to)));
                }
            } else if (item instanceof List<?> pair && pair.size() >= 2) {
                out.add(new Conn(String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
            }
        }
        return out;
    }

    private static Object firstPresent(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    record AssembledLoop(LoopComponent component, Map<String, Object> outputSchema, List<String> bodyIds, String breakId) {}

    private record Conn(String from, String to) {}

    /**
     * Adapts an AssembledNode into a core {@link WorkflowComponent} for LoopGroup.
     * Shares a mutable frame so Studio IR linear body semantics (userFields merge) work
     * without requiring every IR edge to declare {@code inputs_schema}.
     */
    static final class StudioLoopBodyAdapter extends WorkflowComponent {
        private final AssembledNode child;
        private final NodeTypeRegistry registry;
        private final NodeBuildContext ctx;
        private final AtomicReference<Map<String, Object>> frame;

        StudioLoopBodyAdapter(
                AssembledNode child,
                NodeTypeRegistry registry,
                NodeBuildContext ctx,
                AtomicReference<Map<String, Object>> frame) {
            this.child = child;
            this.registry = registry;
            this.ctx = ctx;
            this.frame = frame;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> base = frame.get();
            if (base == null) {
                base = new LinkedHashMap<>();
            }
            Map<String, Object> merged = new LinkedHashMap<>(base);
            if (inputs instanceof Map<?, ?> in) {
                in.forEach((k, v) -> {
                    if (v != null) {
                        merged.put(String.valueOf(k), v);
                    }
                });
            }
            if (session != null) {
                enrichFromSession(merged, session);
            }
            try {
                ComponentExecutable exec = registry.create(child, ctx);
                Object out = exec.invoke(Map.of("userFields", DeepCopies.map(merged)), session, context);
                Map<String, Object> uf = extractUserFields(out);
                // setVariable returns {} (Python None); node outputs live in WorkflowVariableScope
                // (stand-in for NodeSession.set_outputs).
                if (uf.isEmpty() && ctx != null && ctx.variableScope() != null) {
                    uf = new LinkedHashMap<>(ctx.variableScope().snapshot());
                }
                Map<String, Object> next = new LinkedHashMap<>(merged);
                next.putAll(uf);
                frame.set(next);
                return new LinkedHashMap<>(uf);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static void enrichFromSession(Map<String, Object> merged, NodeSessionApi session) {
            for (String k : List.of("item", "index")) {
                if (merged.get(k) != null) {
                    continue;
                }
                try {
                    Object v = session.getState(k);
                    if (v == null && session.getInner() != null && session.getInner().state() != null) {
                        v = session.getInner().state().get(k);
                    }
                    if (v == null && session.getInner() instanceof com.openjiuwen.core.session.internal.NodeSession) {
                        com.openjiuwen.core.session.internal.NodeSession ns =
                                (com.openjiuwen.core.session.internal.NodeSession) session.getInner();
                        if (ns.parent() != null && ns.parent().state() != null) {
                            v = ns.parent().state().get(k);
                        }
                    }
                    if (v != null) {
                        merged.put(k, v);
                    }
                } catch (RuntimeException ignored) {
                    // soft
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> extractUserFields(Object out) {
            if (!(out instanceof Map<?, ?> m)) {
                return Map.of();
            }
            Object uf = m.get("userFields");
            if (uf instanceof Map<?, ?> um) {
                Map<String, Object> copy = new LinkedHashMap<>();
                um.forEach((k, v) -> copy.put(String.valueOf(k), v));
                return copy;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
    }

    /** IR {@code breakCondition} as core {@link LoopBreakComponent}. */
    static final class StudioLoopBreakAdapter extends LoopBreakComponent {
        private final Object breakCond;
        private final AtomicReference<Map<String, Object>> frame;

        StudioLoopBreakAdapter(Object breakCond, AtomicReference<Map<String, Object>> frame) {
            this.breakCond = breakCond;
            this.frame = frame;
        }

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> env = new LinkedHashMap<>();
            if (frame.get() != null) {
                env.putAll(frame.get());
            }
            if (inputs instanceof Map<?, ?> in) {
                in.forEach((k, v) -> env.put(String.valueOf(k), v));
            }
            if (ConditionEvaluator.matches(breakCond, env) || Boolean.TRUE.equals(env.get("__break__"))) {
                return super.invoke(inputs, session, context);
            }
            return Map.of();
        }
    }
}
