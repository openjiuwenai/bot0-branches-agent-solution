/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;
import com.openjiuwen.studio.dsl.util.DeepCopies;
import com.openjiuwen.studio.dsl.util.PathResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.loop — iterate and execute loopBody child nodes when declared (FEAT MUST).
 *
 * @since 2026-08-17
 */
public final class LoopNodeHandler implements NodeHandlerFactory {
    private final NodeTypeRegistry registry;
    /**
     * LoopNodeHandler.
     * @param registry registry
     */
    public LoopNodeHandler(NodeTypeRegistry registry) {
        this.registry = registry;
    }
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.loop";
    }
    /**
     * aliases.
     */
    @Override
    public Set<String> aliases() {
        return Set.of();
    }
    /**
     * create.
     * @param node node
     * @param ctx ctx
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new LoopExecutable(node, registry, ctx);
    }

    static final class LoopExecutable extends AbstractStudioNode {
        private final NodeTypeRegistry registry;
        private final NodeBuildContext ctx;

        LoopExecutable(AssembledNode node, NodeTypeRegistry registry, NodeBuildContext ctx) {
            super(node);
            this.registry = registry;
            this.ctx = ctx;
        }
        /**
         * doInvoke.
         * @param inputs inputs
         * @param session session
         * @param context context
         * @throws Exception when the call fails
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Map<String, Object> configs = node.configs();
            int max = 10;
            Object mi = configs.getOrDefault("maxIterations", configs.get("loopCount"));
            if (mi instanceof Number n) {
                max = Math.max(0, n.intValue());
            }
            Object breakCond = configs.get("breakCondition");
            String forEachKey = stringVal(configs.getOrDefault("forEach", configs.get("arrayKey")));
            List<AssembledNode> body = parseBody(configs.get("loopBody"));
            List<Object> iterations = new ArrayList<>();

            if (!forEachKey.isBlank()) {
                runForEach(uf, forEachKey, max, breakCond, body, session, context, iterations);
            } else {
                runCounted(uf, max, breakCond, body, session, context, iterations);
            }
            uf.put("loopOutputs", iterations);
            uf.put("loopCount", iterations.size());
            return NodePayload.userFields(uf);
        }

        private void runForEach(
                Map<String, Object> uf,
                String forEachKey,
                int max,
                Object breakCond,
                List<AssembledNode> body,
                NodeSessionApi session,
                ModelContext context,
                List<Object> iterations)
                throws Exception {
            Object arr = PathResolver.get(uf, forEachKey).orElse(null);
            if (!(arr instanceof List<?> list)) {
                return;
            }
            int i = 0;
            for (Object item : list) {
                if (i >= max) {
                    break;
                }
                Map<String, Object> frame = new LinkedHashMap<>(uf);
                frame.put("item", item);
                frame.put("index", i);
                if (breakCond != null && ConditionEvaluator.matches(breakCond, frame)) {
                    break;
                }
                Map<String, Object> bodyOut = runBody(frame, session, context, body);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("index", i);
                rec.put("item", item);
                rec.put("bodyOutput", bodyOut);
                iterations.add(rec);
                uf.putAll(bodyOut);
                i++;
            }
        }

        private void runCounted(
                Map<String, Object> uf,
                int max,
                Object breakCond,
                List<AssembledNode> body,
                NodeSessionApi session,
                ModelContext context,
                List<Object> iterations)
                throws Exception {
            for (int i = 0; i < max; i++) {
                Map<String, Object> frame = new LinkedHashMap<>(uf);
                frame.put("index", i);
                if (breakCond != null && ConditionEvaluator.matches(breakCond, frame)) {
                    break;
                }
                Map<String, Object> bodyOut = runBody(frame, session, context, body);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("index", i);
                rec.put("bodyOutput", bodyOut);
                iterations.add(rec);
                uf.putAll(bodyOut);
            }
        }

        private Map<String, Object> runBody(
                Map<String, Object> frame, NodeSessionApi session, ModelContext context, List<AssembledNode> body)
                throws Exception {
            if (body.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("userFields", DeepCopies.map(frame));
            Map<String, Object> lastUf = frame;
            for (AssembledNode child : body) {
                ComponentExecutable exec = registry.create(child, ctx);
                Object out = exec.invoke(current, session, context);
                if (out instanceof Map<?, ?>) {
                    current = asMap(out);
                    lastUf = userFieldsOf(current);
                    current.put("userFields", lastUf);
                }
            }
            return lastUf;
        }

        @SuppressWarnings("unchecked")
        private static List<AssembledNode> parseBody(Object loopBody) {
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
                    out.add(AssembledNode.of(id, type, configs));
                } else {
                    continue;
                }
                i++;
            }
            return out;
        }

        private static Map<String, Object> cast(Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }

        private static String stringVal(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
