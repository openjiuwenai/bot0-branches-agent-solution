/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.end — Studio End (Python end.py invoke / stream frames; mix deferred).
 *
 * @since 2026-08-17
 */
public final class EndNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.end";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new EndExecutable(node);
    }

    static final class EndExecutable extends AbstractStudioNode {
        EndExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            if (EndEngine.alreadyInvoked(session)) {
                return NodePayload.ofFields(Map.of());
            }
            EndEngine.markInvoked(session);

            Map<String, Object> fields = new LinkedHashMap<>(userFieldsOf(inputs));
            EndEngine.applyTypeConversion(fields, EndEngine.inputDefsFromConfigs(node.configs()));

            Map<String, Object> outputs = EndEngine.mapEndPrefixed(fields);
            EndEngine.stripEndPrefixed(fields);

            String template = firstNonBlank(
                    node.configs().get("responseTemplate"),
                    node.configs().get("template"),
                    node.configs().get("response"));
            Map<String, Object> renderVars = new LinkedHashMap<>(fields);
            renderVars.putAll(outputs);
            String answer;
            if (template != null && !template.isBlank()) {
                answer = EndEngine.renderTemplate(template, renderVars);
            } else {
                Object a = outputs.getOrDefault(
                        "answer",
                        fields.getOrDefault("answer", fields.getOrDefault("result", fields.get("response"))));
                if (a == null) {
                    a = outputs.values().stream().findFirst().orElse("");
                }
                answer = a == null ? "" : String.valueOf(a);
            }

            boolean struct =
                    Boolean.TRUE.equals(node.configs().get("isStructMessage"))
                            || Boolean.TRUE.equals(node.configs().get("enable_struct_message"));
            Object structTpl = node.configs().get("struct_output_template");
            String structAnswer = "";
            String originAnswer = answer;
            if (struct && structTpl != null && !String.valueOf(structTpl).isBlank()) {
                Map<String, Object> vars = new LinkedHashMap<>(renderVars);
                vars.put("_NODE_OUTPUT", answer);
                structAnswer = EndEngine.renderTemplate(String.valueOf(structTpl), vars);
            }

            String think = "";
            Object t = fields.getOrDefault("_reasoning_content", fields.get("reasoning_content"));
            if (t == null) {
                t = outputs.get("reasoning_content");
            }
            if (t != null) {
                think = String.valueOf(t);
            }

            boolean endInterrupt = false;
            Object event = node.configs().get("event");
            if (event instanceof Map<?, ?> em && "task_completion".equals(String.valueOf(em.get("type")))) {
                endInterrupt = true;
            }

            String outputMode = stringOrNull(node.configs().getOrDefault("outputMode", node.configs().get("output_mode")));
            String nodeName = String.valueOf(node.configs().getOrDefault("name", node.id()));

            Map<String, Object> userOut = new LinkedHashMap<>(fields);
            userOut.putAll(outputs);
            userOut.put("answer", structAnswer.isBlank() ? answer : structAnswer);
            userOut.put("result", userOut.get("answer"));
            userOut.put("response", answer);
            if (!structAnswer.isBlank()) {
                userOut.put("struct_answer", structAnswer);
                userOut.put("origin_answer", originAnswer);
            }
            userOut.put("__terminal__", true);
            userOut.put("should_interrupt", endInterrupt);

            Map<String, Object> withThink = EndEngine.metadata(
                    structAnswer.isBlank() ? answer : structAnswer,
                    node.id(),
                    nodeName,
                    "jiuwen.end",
                    endInterrupt,
                    think,
                    userOut,
                    outputMode,
                    originAnswer);
            Map<String, Object> withoutThink = EndEngine.metadata(
                    structAnswer.isBlank() ? answer : structAnswer,
                    node.id(),
                    nodeName,
                    "jiuwen.end",
                    endInterrupt,
                    "",
                    userOut,
                    outputMode,
                    originAnswer);
            EndEngine.emitEndFrames(session, withThink, withoutThink);
            return NodePayload.userFields(userOut);
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> in = asMap(inputs);
            Map<String, Object> fields = new LinkedHashMap<>(userFieldsOf(in));
            Map<String, Object> outputs = EndEngine.mapEndPrefixed(fields);
            Map<String, Object> renderVars = new LinkedHashMap<>(fields);
            renderVars.putAll(outputs);
            String template = firstNonBlank(
                    node.configs().get("responseTemplate"),
                    node.configs().get("template"),
                    node.configs().get("response"));
            String answer =
                    template != null && !template.isBlank()
                            ? EndEngine.renderTemplate(template, renderVars)
                            : String.valueOf(fields.getOrDefault("answer", fields.getOrDefault("result", "")));

            List<Object> frames = new java.util.ArrayList<>();
            // start empty marker when template begins with {{
            if (template != null && template.startsWith("{{")) {
                frames.add(Map.of(
                        "type",
                        StudioStreamFrames.PARTIAL_CONTENT,
                        "index",
                        0,
                        "data",
                        Map.of("answer", "", "node_id", node.id(), "node_type", "jiuwen.end")));
            }
            if (!answer.isEmpty()) {
                frames.add(Map.of(
                        "type",
                        StudioStreamFrames.PARTIAL_CONTENT,
                        "index",
                        frames.size(),
                        "data",
                        Map.of("answer", answer, "result", answer, "node_id", node.id(), "node_type", "jiuwen.end")));
            }
            if (template != null && template.endsWith("}}")) {
                frames.add(Map.of(
                        "type",
                        StudioStreamFrames.PARTIAL_CONTENT,
                        "index",
                        frames.size(),
                        "data",
                        Map.of("answer", "", "node_id", node.id(), "node_type", "jiuwen.end")));
            }
            Map<String, Object> meta = EndEngine.metadata(
                    answer, node.id(), String.valueOf(node.configs().getOrDefault("name", node.id())), "jiuwen.end", false, "",
                    Map.copyOf(renderVars), null, "");
            frames.add(Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", frames.size(), "data", meta));
            Map<String, Object> wfEnd = new LinkedHashMap<>(meta);
            wfEnd.put("think", "");
            frames.add(Map.of("type", StudioStreamFrames.WORKFLOW_END, "index", frames.size(), "data", wfEnd));
            return frames.iterator();
        }

        private static String firstNonBlank(Object... vals) {
            for (Object v : vals) {
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
            return null;
        }

        private static String stringOrNull(Object v) {
            return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
        }
    }
}
