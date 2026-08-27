/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowqa;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FlowQA engine — strict 1:1 with Python {@code flow_qa.FlowQA}.
 *
 * @since 2026-08-26
 */

public final class FlowQaEngine {
    public static final String STATE_KEY = "flow_qa_state";
    public static final String USER_FIELDS = "userFields";

    private static final Pattern PLACEHOLDER_SPLIT = Pattern.compile("(\\{\\{.*?\\}\\})");

    private final String nodeId;
    private final FlowQaConfig config;

    public FlowQaEngine(String nodeId, FlowQaConfig config) {
        this.nodeId = nodeId == null ? "qa" : nodeId;
        this.config = config == null ? FlowQaConfig.from(Map.of()) : config;
    }

    /**
     * fromConfigs.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static FlowQaEngine fromConfigs(String nodeId, Map<String, Object> conf) {
        return new FlowQaEngine(nodeId, FlowQaConfig.from(conf));
    }

    /**
     * config.
     *
     * @return result
     * @since 0.1.0
     */

    public FlowQaConfig config() {
        return config;
    }

    /**
     * Python {@code invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        try {
            Map<String, Object> state = loadState(session);
            String status = String.valueOf(state.getOrDefault("status", "start"));

            Map<String, Object> userFields = userFieldsOf(inputs);
            Map<String, Object> renderFields =
                    StructInputSchemas.normalizeStructInputs(userFields, config.structInputSchemas());

            if (config.needReply()) {
                if ("end".equals(status)) {
                    state = new LinkedHashMap<>();
                    state.put("status", "start");
                    status = "start";
                }

                if ("start".equals(status)) {
                    String query = getQaByStrategy(renderFields);
                    writeInteractionStream(session, query, renderFields, true);
                    Map<String, Object> next = new LinkedHashMap<>();
                    next.put("status", "user_interact");
                    next.put("inputs", renderFields);
                    next.put("question", query);
                    storeState(session, next);

                    Object via = tryInteract(session, query);
                    if (via != null) {
                        storeState(session, Map.of("status", "end", "question", query, "inputs", renderFields));
                        return wrapUf(Map.of("response", via));
                    }

                    Map<String, Object> hang = new LinkedHashMap<>(renderFields);
                    hang.put("response", query);
                    hang.put("question", query);
                    hang.put("qaState", "user_interact");
                    hang.put("hangState", "INPUT_REQUIRED");
                    hang.put("STATUS", "INPUT_REQUIRED");
                    hang.put("should_interrupt", true);
                    return wrapUf(hang);
                }

                if ("user_interact".equals(status)) {
                    Object reply = resolveReply(
                            userFields, inputs, session, String.valueOf(state.getOrDefault("question", "")));
                    storeState(
                            session,
                            Map.of(
                                    "status",
                                    "end",
                                    "question",
                                    state.getOrDefault("question", ""),
                                    "inputs",
                                    renderFields));
                    return wrapUf(Map.of("response", reply));
                }
            }

            // needReply=false
            String query = getQaByStrategy(renderFields);
            writeInteractionStream(session, query, renderFields, false);
            storeState(session, Map.of("status", "end", "question", query, "inputs", renderFields));
            return wrapUf(Map.of("response", query));
        } catch (NodeExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            throw new NodeExecutionException(
                    nodeId, FlowQaConfig.TYPE, NodeCauseCode.NODE_INVOKE_FAILED, String.valueOf(e.getMessage()), e);
        }
    }

    String getQaByStrategy(Map<String, Object> inputs) {
        String strategy = config.qaStrategy() == null ? "random" : config.qaStrategy();
        List<String> options = validateOptions(config.options(), inputs);
        if (options.isEmpty()) {
            throw new NodeExecutionException(
                    nodeId, FlowQaConfig.TYPE, NodeCauseCode.NODE_INVOKE_FAILED, "QA options is empty");
        }
        if (!"random".equals(strategy) && !"index".equals(strategy)) {
            throw new NodeExecutionException(
                    nodeId,
                    FlowQaConfig.TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "Unsupported QA strategy: " + strategy);
        }
        if ("random".equals(strategy)) {
            return options.get(ThreadLocalRandom.current().nextInt(options.size()));
        }
        int index = 0;
        Object idx = inputs.get(config.indexKey());
        if (idx instanceof Number n) {
            index = n.intValue();
        } else if (idx != null) {
            try {
                index = Integer.parseInt(String.valueOf(idx));
            } catch (NumberFormatException e) {
                index = 0;
            }
        }
        if (index < 0 || index >= options.size()) {
            throw new NodeExecutionException(
                    nodeId,
                    FlowQaConfig.TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "QA index " + index + " out of range");
        }
        return options.get(index);
    }

    List<String> validateOptions(List<String> options, Map<String, Object> inputs) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            out.add(processValuesOfDict(option, inputs));
        }
        return out;
    }

    /**
     * Python {@code _process_values_of_dict} — missing {{key}} raises.
     *
     * @param originTemplate originTemplate
     * @param inputs inputs
     * @return result
     * @since 0.1.0
     */
    String processValuesOfDict(String originTemplate, Map<String, Object> inputs) {
        if (originTemplate == null) {
        return "";
    }
        java.util.regex.Matcher m = PLACEHOLDER_SPLIT.matcher(originTemplate);
        List<String> tokens = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                tokens.add(originTemplate.substring(last, m.start()));
            }
            tokens.add(m.group(1));
            last = m.end();
        }
        if (last < originTemplate.length()) {
            tokens.add(originTemplate.substring(last));
        }
        if (tokens.isEmpty()) {
            tokens.add(originTemplate);
        }

        StringBuilder response = new StringBuilder();
        for (String res : tokens) {
            if (res.startsWith("{{") && res.endsWith("}}")) {
                String paramName = res.substring(2, res.length() - 2);
                if (!inputs.containsKey(paramName)) {
                    throw new NodeExecutionException(
                            nodeId,
                            FlowQaConfig.TYPE,
                            NodeCauseCode.NODE_INVOKE_FAILED,
                            "QA parameter key not found: " + paramName);
                }
                Object paramValue = inputs.get(paramName);
                response.append(paramValue == null ? "" : String.valueOf(paramValue));
            } else {
                response.append(res);
            }
        }
        return response.toString();
    }

    private void writeInteractionStream(
            NodeSessionApi session, String answer, Map<String, Object> renderInputs, boolean needReply) {
        Map<String, Object> streamRelated = baseStream(answer, needReply);
        Map<String, Object> endStream = streamRelated;

        if (config.enableStructMessage()
                && config.structOutputTemplate() != null
                && !config.structOutputTemplate().isBlank()) {
            Map<String, Object> vars = new LinkedHashMap<>(renderInputs);
            vars.put("_NODE_OUTPUT", answer);
            String structAnswer = TemplateRenderer.render(config.structOutputTemplate(), vars);
            endStream = new LinkedHashMap<>(streamRelated);
            endStream.put("answer", structAnswer);
            endStream.put("origin_answer", answer);
            endStream.put("is_struct_message", true);
        }

        if (session == null) {
            return;
        }
        try {
            session.writeCustomStream(
                    Map.of("type", StudioStreamFrames.PARTIAL_CONTENT, "index", 0, "data", streamRelated));
            session.writeCustomStream(
                    Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", endStream));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private Map<String, Object> baseStream(String query, boolean needReply) {
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("answer", query);
        stream.put("result", query);
        stream.put("node_id", nodeId);
        stream.put("node_name", config.name() == null ? nodeId : config.name());
        stream.put("node_type", FlowQaConfig.TYPE);
        stream.put("should_interrupt", needReply);
        stream.put("enable_history", config.enableHistory());
        stream.put("need_reply", needReply);
        return stream;
    }

    private static Map<String, Object> wrapUf(Map<String, Object> uf) {
        return Map.of(USER_FIELDS, uf);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    private static Object resolveReply(
            Map<String, Object> uf, Map<String, Object> inputs, NodeSessionApi session, String question) {
        Object interactive = uf.getOrDefault("interactiveInput", inputs == null ? null : inputs.get("interactiveInput"));
        Object fromIi = unwrapInteractive(interactive);
        if (fromIi != null) {
            return fromIi;
        }
        if (interactive != null && !(interactive instanceof InteractiveInput)) {
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
            } catch (RuntimeException e) {
                rethrowGraphInterrupt(e);
            }
        }
        return uf.getOrDefault("response", uf.get("input"));
    }

    private static Object unwrapInteractive(Object interactive) {
        if (!(interactive instanceof InteractiveInput ii)) {
        return null;
    }
        if (ii.getRawInputs() != null) {
            return ii.getRawInputs();
        }
        if (ii.getUserInputs() != null && !ii.getUserInputs().isEmpty()) {
            return ii.getUserInputs().values().stream().reduce((a, b) -> b).orElse(null);
        }
        return null;
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
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
        }
        try {
            return session.interact(question);
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            return null;
        }
    }

    private static void rethrowGraphInterrupt(RuntimeException e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof GraphInterrupt
                    || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                throw e;
            }
            cur = cur.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadState(NodeSessionApi session) {
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

    static void storeState(NodeSessionApi session, Map<String, Object> state) {
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
