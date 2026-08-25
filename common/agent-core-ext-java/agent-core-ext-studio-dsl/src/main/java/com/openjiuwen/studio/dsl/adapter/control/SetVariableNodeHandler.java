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
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.store.ConversationValsStore;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * jiuwen.setVariable — operators + MEMORY_VARIABLE / session Redis (Python LoopSetVariable).
 *
 * @since 2026-08-17
 */
public final class SetVariableNodeHandler implements NodeHandlerFactory {
    private static final String MEMORY_PREFIX = "MEMORY_VARIABLE.";

    @Override
    public String canonicalType() {
        return "jiuwen.setVariable";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new SetVariableExecutable(
                node,
                ctx.variableScope(),
                ctx.workflowId(),
                ConversationValsStores.get());
    }

    static final class SetVariableExecutable extends AbstractStudioNode {
        private final WorkflowVariableScope scope;
        private final String workflowId;
        private final ConversationValsStore valsStore;

        SetVariableExecutable(
                AssembledNode node,
                WorkflowVariableScope scope,
                String workflowId,
                ConversationValsStore valsStore) {
            super(node);
            this.scope = scope;
            this.workflowId = workflowId;
            this.valsStore = valsStore == null ? ConversationValsStores.get() : valsStore;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> vars = new LinkedHashMap<>(scope.snapshot());
            vars.putAll(userFieldsOf(inputs));
            Map<String, Object> sessionVarDefs = loadSessionVarDefs(session);
            Map<String, Object> redisPatch = new LinkedHashMap<>();
            Map<String, String> operators = operatorMapping(node.configs());

            Object settings = node.configs().get("settings");
            if (settings instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        applyAssignment(
                                vars,
                                redisPatch,
                                sessionVarDefs,
                                extractValue(m.get("left")).orElse(null),
                                extractRaw(m.get("right")),
                                operators.getOrDefault(String.valueOf(m.get("left")), ""));
                    }
                }
            } else {
                Object mapping =
                        node.configs().getOrDefault("variableMapping", node.configs().get("variable_mapping"));
                if (mapping instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> applyAssignment(
                            vars,
                            redisPatch,
                            sessionVarDefs,
                            String.valueOf(k),
                            v,
                            operators.getOrDefault(String.valueOf(k), "")));
                }
            }

            scope.putAll(vars);
            persistLocal(session, vars);
            persistRedis(session, inputs, sessionVarDefs, redisPatch);

            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("userFields", vars);
            wrap.put("__workflowVars__", Map.copyOf(scope.snapshot()));
            return NodePayload.ofFields(wrap);
        }

        private void applyAssignment(
                Map<String, Object> vars,
                Map<String, Object> redisPatch,
                Map<String, Object> sessionVarDefs,
                String left,
                Object right,
                String operator) {
            if (left == null || left.isBlank()) {
                return;
            }
            String key = stripRef(left);
            Object value = applyOperator(vars, key, right, operator);
            if (key.startsWith(MEMORY_PREFIX)) {
                String varName = key.substring(MEMORY_PREFIX.length());
                vars.put(key, value);
                vars.put(varName, value);
                if (sessionVarDefs.containsKey(varName)) {
                    redisPatch.put(varName, value);
                }
                return;
            }
            // memory.xxx or plain name
            String simple = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            vars.put(simple, value);
            vars.put(key, value);
            if (sessionVarDefs.containsKey(simple)
                    && (key.contains(".memory.") || key.endsWith(".memory." + simple) || key.equals(simple))) {
                redisPatch.put(simple, value);
            }
        }

        private Object applyOperator(Map<String, Object> vars, String key, Object right, String operator) {
            String simple = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            Object current = vars.getOrDefault(key, vars.get(simple));
            return switch (operator == null ? "" : operator) {
                case "increment" -> toLong(current) + 1;
                case "decrement" -> toLong(current) - 1;
                case "empty" -> null;
                case "empty_str" -> "";
                case "empty_arr" -> List.of();
                default -> right;
            };
        }

        private void persistRedis(
                NodeSessionApi session,
                Map<String, Object> inputs,
                Map<String, Object> sessionVarDefs,
                Map<String, Object> redisPatch) {
            if (redisPatch.isEmpty() || sessionVarDefs.isEmpty()) {
                return;
            }
            String wf = workflowId == null || workflowId.isBlank() ? "wf" : workflowId;
            String conversationId = resolveConversationId(inputs, session);
            if (conversationId == null || conversationId.isBlank()) {
                return;
            }
            String key = StartAssignmentSupport.redisKey(wf, conversationId);
            Map<String, Object> merged = new LinkedHashMap<>();
            try {
                Map<String, Object> old = valsStore.getMap(key);
                if (old != null) {
                    merged.putAll(old);
                }
            } catch (RuntimeException ignored) {
                // soft-fail like Python
            }
            merged.putAll(redisPatch);
            try {
                valsStore.setMap(key, merged, StartAssignmentSupport.conversationTtlSeconds());
            } catch (RuntimeException ignored) {
                // soft-fail
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> loadSessionVarDefs(NodeSessionApi session) {
            if (session == null) {
                return Map.of();
            }
            try {
                Object defs = session.getGlobalState("_session_var_defs");
                if (defs instanceof Map<?, ?> m) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
            } catch (RuntimeException ignored) {
                // mock
            }
            return Map.of();
        }

        private static String resolveConversationId(Map<String, Object> inputs, NodeSessionApi session) {
            Map<String, Object> uf = userFieldsOf(inputs);
            Object c = uf.get("conversationId");
            if (c != null && !String.valueOf(c).isBlank()) {
                return String.valueOf(c);
            }
            if (inputs != null && inputs.get("conversationId") != null) {
                return String.valueOf(inputs.get("conversationId"));
            }
            if (session != null) {
                try {
                    Object g = session.getGlobalState("conversationId");
                    if (g != null && !String.valueOf(g).isBlank()) {
                        return String.valueOf(g);
                    }
                    String s = session.getSessionId();
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                } catch (RuntimeException ignored) {
                    // mock
                }
            }
            return "";
        }

        private static Map<String, String> operatorMapping(Map<String, Object> configs) {
            Object raw = configs.getOrDefault("operatorMapping", configs.get("operator_mapping"));
            Map<String, String> out = new LinkedHashMap<>();
            if (raw instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            }
            return out;
        }

        private static String stripRef(String left) {
            String s = left.trim();
            if (s.startsWith("${") && s.endsWith("}")) {
                s = s.substring(2, s.length() - 1);
            }
            return s;
        }

        private static long toLong(Object v) {
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v == null) {
                return 0L;
            }
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        private void persistLocal(NodeSessionApi session, Map<String, Object> vars) {
            if (session == null) {
                return;
            }
            try {
                session.updateState(Map.copyOf(vars));
            } catch (RuntimeException ignored) {
                // mock
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
