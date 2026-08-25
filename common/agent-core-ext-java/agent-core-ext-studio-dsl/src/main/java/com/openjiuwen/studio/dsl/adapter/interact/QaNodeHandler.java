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
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * EI.qa — FlowQA (options + random/index; needReply → INPUT_REQUIRED).
 *
 * @since 2026-08-25
 */
public final class QaNodeHandler implements NodeHandlerFactory {
    static final String STATE_KEY = "flow_qa_state";

    @Override
    public String canonicalType() {
        return "EI.qa";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ei.qa", "jiuwen.flowQa");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new QaExecutable(node);
    }

    static final class QaExecutable extends AbstractStudioNode {
        QaExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            boolean needReply =
                    Boolean.TRUE.equals(node.configs().get("needReply"))
                            || "true".equalsIgnoreCase(String.valueOf(node.configs().getOrDefault("needReply", false)));

            Map<String, Object> state = loadState(session);
            String status = String.valueOf(state.getOrDefault("status", "start"));

            if (needReply && "user_interact".equals(status)) {
                Object reply = resolveReply(uf, inputs, session, String.valueOf(state.getOrDefault("question", "")));
                storeState(session, Map.of("status", "end", "question", state.getOrDefault("question", ""), "inputs", uf));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("response", reply);
                out.put("qaState", "end");
                return NodePayload.userFields(out);
            }

            String query = pickOption(uf);
            Map<String, Object> stream = baseStream(query, needReply);

            boolean struct =
                    Boolean.TRUE.equals(node.configs().get("isStructMessage"))
                            || Boolean.TRUE.equals(node.configs().get("enable_struct_message"));
            Object structTpl = node.configs().get("struct_output_template");
            Map<String, Object> endData = stream;
            if (struct && structTpl != null && !String.valueOf(structTpl).isBlank()) {
                Map<String, Object> vars = new LinkedHashMap<>(uf);
                vars.put("_NODE_OUTPUT", query);
                String structAnswer = TemplateRenderer.render(String.valueOf(structTpl), vars);
                endData = new LinkedHashMap<>(stream);
                endData.put("answer", structAnswer);
                endData.put("origin_answer", query);
                endData.put("is_struct_message", true);
            }

            if (session != null) {
                try {
                    session.writeCustomStream(
                            Map.of("type", StudioStreamFrames.PARTIAL_CONTENT, "index", 0, "data", stream));
                    session.writeCustomStream(
                            Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", endData));
                } catch (RuntimeException ignored) {
                    // mock
                }
            }

            if (!needReply) {
                storeState(session, Map.of("status", "end", "question", query, "inputs", uf));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("response", query);
                out.put("qaState", "end");
                return NodePayload.userFields(out);
            }

            Map<String, Object> interact = new LinkedHashMap<>();
            interact.put("status", "user_interact");
            interact.put("question", query);
            interact.put("inputs", uf);
            storeState(session, interact);

            Object via = tryInteract(session, query);
            if (via != null) {
                storeState(session, Map.of("status", "end", "question", query, "inputs", uf));
                return NodePayload.userFields(Map.of("response", via, "qaState", "end"));
            }

            Map<String, Object> hang = new LinkedHashMap<>(uf);
            hang.put("response", query);
            hang.put("question", query);
            hang.put("qaState", "user_interact");
            hang.put("hangState", "INPUT_REQUIRED");
            hang.put("STATUS", "INPUT_REQUIRED");
            hang.put("should_interrupt", true);
            return NodePayload.userFields(hang);
        }

        private Map<String, Object> baseStream(String query, boolean needReply) {
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("answer", query);
            stream.put("result", query);
            stream.put("node_id", node.id());
            stream.put("node_name", node.configs().getOrDefault("name", node.id()));
            stream.put("node_type", "EI.qa");
            stream.put("should_interrupt", needReply);
            stream.put("need_reply", needReply);
            Object eh = node.configs().getOrDefault("enable_history", true);
            stream.put("enable_history", !"false".equalsIgnoreCase(String.valueOf(eh)));
            return stream;
        }

        @SuppressWarnings("unchecked")
        private String pickOption(Map<String, Object> uf) {
            Object raw = node.configs().get("options");
            List<String> options = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    options.add(TemplateRenderer.render(String.valueOf(o), uf));
                }
            }
            if (options.isEmpty()) {
                throw new NodeExecutionException(
                        node.id(), node.canonicalType(), NodeCauseCode.NODE_INVOKE_FAILED, "QA options is empty");
            }
            String strategy = String.valueOf(node.configs().getOrDefault("qaStrategy", "random")).toLowerCase();
            if ("index".equals(strategy)) {
                String indexKey = String.valueOf(node.configs().getOrDefault("index_key", "index"));
                int index = 0;
                Object idx = uf.get(indexKey);
                if (idx instanceof Number n) {
                    index = n.intValue();
                } else if (idx != null) {
                    try {
                        index = Integer.parseInt(String.valueOf(idx));
                    } catch (NumberFormatException ignored) {
                        index = 0;
                    }
                }
                if (index < 0 || index >= options.size()) {
                    throw new NodeExecutionException(
                            node.id(),
                            node.canonicalType(),
                            NodeCauseCode.NODE_INVOKE_FAILED,
                            "QA index " + index + " out of range");
                }
                return options.get(index);
            }
            return options.get(ThreadLocalRandom.current().nextInt(options.size()));
        }

        private static Object resolveReply(
                Map<String, Object> uf, Map<String, Object> inputs, NodeSessionApi session, String question) {
            if (uf.containsKey("response") && !"user_interact".equals(uf.get("qaState"))) {
                // only if explicitly a user reply field distinct from question echo — prefer interactive
            }
            Object interactive = uf.getOrDefault("interactiveInput", inputs.get("interactiveInput"));
            if (interactive != null) {
                return interactive;
            }
            if (uf.containsKey("userReply")) {
                return uf.get("userReply");
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
            }
            return uf.getOrDefault("response", uf.get("input"));
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
                Object all = session.getState(null);
                if (all instanceof Map<?, ?> map) {
                    Object nested = map.get(STATE_KEY);
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
    }
}
