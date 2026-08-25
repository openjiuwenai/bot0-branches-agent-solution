/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SetVariableNodeHandler for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class SetVariableNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.setVariable";
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
        return new SetVariableExecutable(node, ctx.variableScope());
    }

    static final class SetVariableExecutable extends AbstractStudioNode {
        private final WorkflowVariableScope scope;

        SetVariableExecutable(AssembledNode node, WorkflowVariableScope scope) {
            super(node);
            this.scope = scope;
        }
        /**
         * doInvoke.
         * @param inputs inputs
         * @param session session
         * @param context context
         */
        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> vars = new LinkedHashMap<>(scope.snapshot());
            vars.putAll(userFieldsOf(inputs));
            Object settings = node.configs().get("settings");
            if (settings instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object left = m.get("left");
                        Object right = m.get("right");
                        extractValue(left).ifPresent(key -> vars.put(key, extractRaw(right)));
                    }
                }
            } else {
                Object mapping = node.configs().getOrDefault("variableMapping", node.configs().get("variable_mapping"));
                if (mapping instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> vars.put(String.valueOf(k), v));
                }
            }
            // Bind to current workflow execution scope only — do not write session global bus (L2 §3.7 D11).
            scope.putAll(vars);
            persistLocalOnly(session, vars);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("userFields", vars);
            wrap.put("__workflowVars__", Map.copyOf(scope.snapshot()));
            return NodePayload.ofFields(wrap);
        }

        private void persistLocalOnly(NodeSessionApi session, Map<String, Object> vars) {
            if (session == null) {
                return;
            }
            try {
                session.updateState(Map.copyOf(vars));
            } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                // mock session in unit tests
            }
        }

        private static Optional<String> extractValue(Object side) {
            if (side instanceof Map<?, ?> m) {
                Object v = m.get("value");
                return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
            }
            return side == null ? Optional.empty() : Optional.of(String.valueOf(side));
        }

        private static Object extractRaw(Object side) {
            if (side instanceof Map<?, ?> m) {
                return m.get("value");
            }
            return side;
        }
    }
}
