/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.flowend.FlowEndEngine;
import com.openjiuwen.studio.dsl.flowend.FlowEndGeneratorSupport;
import com.openjiuwen.studio.dsl.flowend.FlowEndMixCoordinator;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.end — strict 1:1 with Python {@code jiuwen/extension/workflow_node/end.py}.
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

    public static final class EndExecutable extends AbstractStudioNode {
        private volatile Map<String, Object> streamOutput;
        private volatile boolean pendingMix;
        private volatile Boolean pendingExpectMix;

        EndExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        public void setMix() {
            pendingMix = true;
        }

        public void setExpectMix(boolean expect) {
            pendingExpectMix = expect;
        }

        public boolean isMix() {
            return pendingMix;
        }

        private FlowEndMixCoordinator mixOf(NodeSessionApi session) {
            FlowEndMixCoordinator mix = FlowEndEngine.mixCoordinator(session, node.id());
            if (pendingMix) {
                mix.setMix();
                pendingMix = false;
            }
            if (pendingExpectMix != null) {
                mix.setExpectMix(pendingExpectMix);
                pendingExpectMix = null;
            }
            return mix;
        }

        public Map<String, Object> getStreamOutput() {
            return streamOutput;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            if (FlowEndEngine.alreadyInvoked(session, node.id())) {
                return NodePayload.ofFields(Map.of());
            }
            FlowEndEngine.markInvoked(session, node.id());

            Prepared prepared = prepareBatchInputs(inputs);
            FlowEndMixCoordinator.MixResult mixResult =
                    mixOf(session).coordinate("batch", prepared.fields, prepared.outputs);
            if (!mixResult.isRenderer()) {
                return NodePayload.ofFields(Map.of());
            }
            Map<String, Object> fields = drainGeneratorsForBatch(mixResult.inputs());
            Map<String, Object> outputs = FlowEndEngine.mapEndPrefixed(fields);
            FlowEndEngine.stripEndPrefixed(fields);
            outputs.putAll(mixResult.outputs());

            NodePayload payload = renderAndEmit(fields, outputs, session, true);
            mixOf(session).markRenderComplete();
            return payload;
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            if (FlowEndEngine.alreadyStreamed(session, node.id())) {
                return List.of().iterator();
            }
            FlowEndEngine.markStreamed(session, node.id());
            Prepared prepared = prepareBatchInputs(asMap(inputs));
            FlowEndMixCoordinator.MixResult mixResult =
                    mixOf(session).coordinate("batch", prepared.fields, prepared.outputs);
            if (!mixResult.isRenderer()) {
                return List.of().iterator();
            }
            Map<String, Object> fields = drainGeneratorsForBatch(mixResult.inputs());
            Map<String, Object> outputs = FlowEndEngine.mapEndPrefixed(fields);
            FlowEndEngine.stripEndPrefixed(fields);
            outputs.putAll(mixResult.outputs());

            List<Object> frames = buildStreamFrames(fields, outputs);
            for (Object f : frames) {
                writeFrame(session, f);
            }
            mixOf(session).markRenderComplete();
            return frames.iterator();
        }

        @Override
        public Object collect(Object inputs, NodeSessionApi session, ModelContext context) {
            if (FlowEndEngine.alreadyCollected(session, node.id())) {
                return null;
            }
            FlowEndEngine.markCollected(session, node.id());
            Map<String, Object> finalInputs = collectChunks(inputs);
            Prepared prepared = prepareFromFields(finalInputs);
            FlowEndMixCoordinator.MixResult mixResult =
                    mixOf(session).coordinate("stream", prepared.fields, prepared.outputs);
            if (!mixResult.isRenderer()) {
                return null;
            }
            Map<String, Object> fields = drainGeneratorsForBatch(mixResult.inputs());
            Map<String, Object> outputs = FlowEndEngine.mapEndPrefixed(fields);
            FlowEndEngine.stripEndPrefixed(fields);
            outputs.putAll(mixResult.outputs());

            NodePayload payload = renderAndEmit(fields, outputs, session, true);
            mixOf(session).markRenderComplete();
            return payload.toInvokeMap();
        }

        @Override
        public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
            if (FlowEndEngine.alreadyTransformed(session, node.id())) {
                return List.of().iterator();
            }
            FlowEndEngine.markTransformed(session, node.id());
            streamOutput = null;
            Map<String, Object> in = asMap(inputs);
            Map<String, Object> fields = new LinkedHashMap<>(userFieldsOf(in));
            // finish-mode metadata: drop last agg frame when messages_type=finish
            Object meta = fields.remove("__stream_metadata__");
            int metaCount = 0;
            if (meta instanceof Iterator<?> mit) {
                Object last = null;
                while (mit.hasNext()) {
                    last = mit.next();
                    metaCount++;
                }
                meta = last;
            }
            boolean finish =
                    meta instanceof Map<?, ?> mm && "finish".equals(String.valueOf(mm.get("messages_type")));
            if (finish) {
                for (Map.Entry<String, Object> e : List.copyOf(fields.entrySet())) {
                    if (e.getValue() instanceof Iterator<?> it) {
                        fields.put(e.getKey(), skipLastIfAgg(it, metaCount));
                    }
                }
            }

            FlowEndEngine.applyTypeConversion(fields, FlowEndEngine.inputDefsFromConfigs(node.configs()));
            // Keep live Iterators for template vars; only share aliases
            Map<String, String> outToIn = FlowEndGeneratorSupport.buildOutputToInput(fields);
            for (Map.Entry<String, String> e : outToIn.entrySet()) {
                Object src = fields.get(e.getValue());
                if (!(src instanceof Iterator || src instanceof Iterable)) {
                    fields.put(e.getKey(), src);
                }
            }
            Map<String, Object> outputs = FlowEndEngine.mapEndPrefixed(fields);
            // For mix, stream path coordinates with batch
            FlowEndMixCoordinator.MixResult mixResult = mixOf(session).coordinate("stream", fields, outputs);
            if (!mixResult.isRenderer()) {
                return List.of().iterator();
            }
            fields = new LinkedHashMap<>(mixResult.inputs());
            outputs = new LinkedHashMap<>(mixResult.outputs());
            outputs.putAll(FlowEndEngine.mapEndPrefixed(fields));

            List<Object> frames = buildTransformFrames(fields, outputs, session);
            mixOf(session).markRenderComplete();
            return frames.iterator();
        }

        private Prepared prepareBatchInputs(Map<String, Object> inputs) {
            Map<String, Object> fields = new LinkedHashMap<>(userFieldsOf(inputs));
            return prepareFromFields(fields);
        }

        private Prepared prepareFromFields(Map<String, Object> fieldsIn) {
            Map<String, Object> fields = new LinkedHashMap<>(fieldsIn == null ? Map.of() : fieldsIn);
            Map<String, String> outToIn = FlowEndGeneratorSupport.buildOutputToInput(fields);
            // Python: type convert before process_generator_values_of_output
            FlowEndEngine.applyTypeConversion(fields, FlowEndEngine.inputDefsFromConfigs(node.configs()));
            fields = FlowEndGeneratorSupport.processGeneratorValues(fields, outToIn, null);
            Map<String, Object> outputs = FlowEndEngine.mapEndPrefixed(fields);
            FlowEndEngine.stripEndPrefixed(fields);
            return new Prepared(fields, outputs);
        }

        private static Map<String, Object> drainGeneratorsForBatch(Map<String, Object> inputs) {
            return FlowEndGeneratorSupport.processGeneratorValues(
                    new LinkedHashMap<>(inputs == null ? Map.of() : inputs),
                    FlowEndGeneratorSupport.buildOutputToInput(inputs),
                    null);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> collectChunks(Object inputs) {
            if (inputs instanceof Iterator<?> it) {
                List<Object> chunks = new ArrayList<>();
                while (it.hasNext()) {
                    chunks.add(it.next());
                }
                if (chunks.isEmpty()) {
                    return Map.of();
                }
                Object last = chunks.get(chunks.size() - 1);
                if (last instanceof Map<?, ?>) {
                    return userFieldsOf(asMap(last));
                }
                return Map.of("value", last);
            }
            if (inputs instanceof Iterable<?> iterable) {
                return collectChunks(iterable.iterator());
            }
            Map<String, Object> m = asMap(inputs);
            return userFieldsOf(m);
        }

        private NodePayload renderAndEmit(
                Map<String, Object> fields, Map<String, Object> outputs, NodeSessionApi session, boolean emit) {
            String template = firstNonBlank(
                    node.configs().get("responseTemplate"),
                    node.configs().get("template"),
                    node.configs().get("response"));
            Map<String, Object> renderVars = new LinkedHashMap<>(fields);
            renderVars.putAll(outputs);
            String answer;
            if (template != null && !template.isBlank()) {
                answer = FlowEndEngine.renderTemplate(template, renderVars);
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
                structAnswer = FlowEndEngine.renderTemplate(String.valueOf(structTpl), vars);
            }

            String think = thinkOf(fields, outputs);
            boolean endInterrupt = endInterrupt();
            String outputMode =
                    stringOrNull(node.configs().getOrDefault("outputMode", node.configs().get("output_mode")));
            String nodeName = String.valueOf(node.configs().getOrDefault("name", node.id()));
            String query = FlowEndEngine.queryOf(session);

            // Python: user_fields = {**inputs, **outputs, **{"query": query}}
            // Studio host: also mark __terminal__ / mirror answer|result|response for chain consumers
            Map<String, Object> userOut = FlowEndEngine.buildUserFields(fields, outputs, query, endInterrupt);
            userOut.put("answer", structAnswer.isBlank() ? answer : structAnswer);
            userOut.put("result", userOut.get("answer"));
            userOut.put("response", answer);
            if (!structAnswer.isBlank()) {
                userOut.put("struct_answer", structAnswer);
                userOut.put("origin_answer", originAnswer);
            }

            Map<String, Object> withThink = FlowEndEngine.metadata(
                    structAnswer.isBlank() ? answer : structAnswer,
                    node.id(),
                    nodeName,
                    "jiuwen.end",
                    endInterrupt,
                    think,
                    userOut,
                    outputMode,
                    originAnswer);
            Map<String, Object> withoutThink = FlowEndEngine.metadata(
                    structAnswer.isBlank() ? answer : structAnswer,
                    node.id(),
                    nodeName,
                    "jiuwen.end",
                    endInterrupt,
                    "",
                    userOut,
                    outputMode,
                    originAnswer);
            if (emit) {
                FlowEndEngine.emitEndFrames(session, withThink, withoutThink);
            }
            streamOutput = new LinkedHashMap<>();
            streamOutput.put("answer", structAnswer.isBlank() ? answer : structAnswer);
            streamOutput.put("node_id", node.id());
            streamOutput.put("node_type", "jiuwen.end");
            if (!structAnswer.isBlank()) {
                streamOutput.put("origin_answer", originAnswer);
            }
            if (outputMode != null) {
                streamOutput.put("output_mode", outputMode);
            }
            streamOutput.put("user_fields", userOut);
            // Python invoke returns get_output_data_with_metadata(...) flat map (not wrapped-only userFields)
            return NodePayload.ofFields(withThink);
        }

        private List<Object> buildStreamFrames(Map<String, Object> fields, Map<String, Object> outputs) {
            String template = firstNonBlank(
                    node.configs().get("responseTemplate"),
                    node.configs().get("template"),
                    node.configs().get("response"));
            Map<String, Object> renderVars = new LinkedHashMap<>(fields);
            renderVars.putAll(outputs);
            List<Object> frames = new ArrayList<>();
            boolean endInterrupt = endInterrupt();
            String outputMode =
                    stringOrNull(node.configs().getOrDefault("outputMode", node.configs().get("output_mode")));
            String nodeName = String.valueOf(node.configs().getOrDefault("name", node.id()));

            if (template != null && !template.isBlank() && hasIteratorVar(template, renderVars)) {
                // True streaming: emit each template segment / iterator chunk
                if (FlowEndEngine.shouldEmitStartMarker(template)) {
                    frames.add(partialFrame(0, "", nodeName, endInterrupt, outputMode, renderVars));
                }
                int index = frames.size();
                for (Object chunk : expandTemplateChunks(template, renderVars)) {
                    frames.add(partialFrame(index++, String.valueOf(chunk), nodeName, endInterrupt, outputMode, renderVars));
                }
                if (FlowEndEngine.shouldEmitEndMarker(template)) {
                    frames.add(partialFrame(frames.size(), "", nodeName, endInterrupt, outputMode, renderVars));
                }
            } else {
                String answer =
                        template != null && !template.isBlank()
                                ? FlowEndEngine.renderTemplate(template, renderVars)
                                : String.valueOf(fields.getOrDefault("answer", fields.getOrDefault("result", "")));
                if (template != null && FlowEndEngine.shouldEmitStartMarker(template)) {
                    frames.add(partialFrame(0, "", nodeName, endInterrupt, outputMode, renderVars));
                }
                if (answer != null && !answer.isEmpty()) {
                    frames.add(partialFrame(frames.size(), answer, nodeName, endInterrupt, outputMode, renderVars));
                }
                if (template != null && FlowEndEngine.shouldEmitEndMarker(template)) {
                    frames.add(partialFrame(frames.size(), "", nodeName, endInterrupt, outputMode, renderVars));
                }
            }

            String finalAnswer = joinPartialAnswers(frames);
            String query = "";
            Map<String, Object> userOut = FlowEndEngine.buildUserFields(renderVars, Map.of(), query, endInterrupt);
            userOut.put("answer", finalAnswer);
            userOut.put("result", finalAnswer);
            userOut.put("response", finalAnswer);
            Map<String, Object> meta = FlowEndEngine.metadata(
                    finalAnswer, node.id(), nodeName, "jiuwen.end", endInterrupt, "", userOut, outputMode, "");
            frames.add(Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", frames.size(), "data", meta));
            Map<String, Object> wfEnd = new LinkedHashMap<>(meta);
            wfEnd.put("think", "");
            frames.add(Map.of("type", StudioStreamFrames.WORKFLOW_END, "index", frames.size(), "data", wfEnd));
            streamOutput = new LinkedHashMap<>(meta);
            return frames;
        }

        private List<Object> buildTransformFrames(
                Map<String, Object> fields, Map<String, Object> outputs, NodeSessionApi session) {
            Map<String, Object> work = new LinkedHashMap<>(fields == null ? Map.of() : fields);
            // Keep a copy of answer-like generators for live partial frames before drain
            Object answerGen = firstIterator(work, "answer", "#end_answer", "result");
            if (answerGen == null) {
                answerGen = firstIterator(outputs, "answer", "#end_answer", "result");
            }
            List<Object> liveFrames = new ArrayList<>();
            String nodeName = String.valueOf(node.configs().getOrDefault("name", node.id()));
            boolean endInterrupt = endInterrupt();
            String outputMode =
                    stringOrNull(node.configs().getOrDefault("outputMode", node.configs().get("output_mode")));
            if (answerGen instanceof Iterator<?> it) {
                // Drain into buffer so shared #end_/alias iterators are not double-consumed later
                List<Object> buf = new ArrayList<>();
                while (it.hasNext()) {
                    buf.add(it.next());
                }
                int i = 0;
                for (Object chunk : buf) {
                    liveFrames.add(partialFrame(i++, String.valueOf(chunk), nodeName, endInterrupt, outputMode, work));
                }
                String joined = buf.stream().map(String::valueOf).reduce("", String::concat);
                putAnswerAliases(work, joined);
            }

            Map<String, Object> drained = FlowEndGeneratorSupport.processGeneratorValues(
                    work, FlowEndGeneratorSupport.buildOutputToInput(work), null);
            Map<String, Object> drainedOut = new LinkedHashMap<>();
            if (outputs != null) {
                for (Map.Entry<String, Object> e : outputs.entrySet()) {
                    if (!FlowEndGeneratorSupport.isGenerator(e.getValue())) {
                        drainedOut.put(e.getKey(), e.getValue());
                    }
                }
            }
            drainedOut.putAll(FlowEndEngine.mapEndPrefixed(drained));
            FlowEndEngine.stripEndPrefixed(drained);

            List<Object> frames = buildStreamFrames(drained, drainedOut);
            if (!liveFrames.isEmpty()) {
                // Prefer live partials + terminal frames (drop duplicate full-answer partial from buildStreamFrames)
                List<Object> terminals = new ArrayList<>();
                for (Object f : frames) {
                    if (f instanceof Map<?, ?> m) {
                        String type = String.valueOf(m.get("type"));
                        if (StudioStreamFrames.MESSAGE_NODE_END.equals(type)
                                || StudioStreamFrames.WORKFLOW_END.equals(type)) {
                            terminals.add(f);
                        }
                    }
                }
                frames = new ArrayList<>(liveFrames);
                frames.addAll(terminals);
            }
            for (Object f : frames) {
                writeFrame(session, f);
            }
            return frames;
        }

        private static void putAnswerAliases(Map<String, Object> work, String joined) {
            for (String k : List.copyOf(work.keySet())) {
                if ("answer".equals(k)
                        || "result".equals(k)
                        || (k != null && k.startsWith(FlowEndEngine.OUTPUT_PREFIX) && k.endsWith("answer"))) {
                    work.put(k, joined);
                }
            }
            if (work.containsKey("#end_answer") || work.containsKey("answer")) {
                work.putIfAbsent("answer", joined);
                work.putIfAbsent("#end_answer", joined);
            }
        }

        private Map<String, Object> partialFrame(
                int index,
                String answer,
                String nodeName,
                boolean endInterrupt,
                String outputMode,
                Map<String, Object> outputs) {
            Map<String, Object> data = FlowEndEngine.metadata(
                    answer, node.id(), nodeName, "jiuwen.end", endInterrupt, "", outputs, outputMode, "");
            return Map.of("type", StudioStreamFrames.PARTIAL_CONTENT, "index", index, "data", data);
        }

        private boolean endInterrupt() {
            Object event = node.configs().get("event");
            return event instanceof Map<?, ?> em && "task_completion".equals(String.valueOf(em.get("type")));
        }

        private static String thinkOf(Map<String, Object> fields, Map<String, Object> outputs) {
            Object t = fields.getOrDefault("_reasoning_content", fields.get("reasoning_content"));
            if (t == null) {
                t = outputs.get("reasoning_content");
            }
            return t == null ? "" : String.valueOf(t);
        }

        private static boolean hasIteratorVar(String template, Map<String, Object> vars) {
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                if ((e.getValue() instanceof Iterator || e.getValue() instanceof Iterable)
                        && template.contains("{{" + e.getKey() + "}}")) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> expandTemplateChunks(String template, Map<String, Object> vars) {
            List<String> parts = new ArrayList<>();
            // naive split on {{var}} keeping order
            String remaining = template;
            while (!remaining.isEmpty()) {
                int start = remaining.indexOf("{{");
                if (start < 0) {
                    parts.add(remaining);
                    break;
                }
                if (start > 0) {
                    parts.add(remaining.substring(0, start));
                }
                int end = remaining.indexOf("}}", start);
                if (end < 0) {
                    parts.add(remaining.substring(start));
                    break;
                }
                String var = remaining.substring(start + 2, end).trim();
                Object val = vars.get(var);
                if (val instanceof Iterator<?> it) {
                    while (it.hasNext()) {
                        parts.add(String.valueOf(it.next()));
                    }
                } else if (val instanceof Iterable<?> iterable) {
                    for (Object o : iterable) {
                        parts.add(String.valueOf(o));
                    }
                } else {
                    parts.add(val == null ? "" : String.valueOf(val));
                }
                remaining = remaining.substring(end + 2);
            }
            return parts;
        }

        private static String joinPartialAnswers(List<Object> frames) {
            StringBuilder sb = new StringBuilder();
            for (Object f : frames) {
                if (f instanceof Map<?, ?> m
                        && StudioStreamFrames.PARTIAL_CONTENT.equals(String.valueOf(m.get("type")))
                        && m.get("data") instanceof Map<?, ?> d) {
                    Object a = d.get("answer");
                    if (a != null) {
                        sb.append(a);
                    }
                }
            }
            return sb.toString();
        }

        private static Object firstIterator(Map<String, Object> m, String... keys) {
            for (String k : keys) {
                Object v = m.get(k);
                if (v instanceof Iterator || v instanceof Iterable) {
                    return v instanceof Iterable<?> it ? it.iterator() : v;
                }
            }
            return null;
        }

        private static Iterator<?> skipLastIfAgg(Iterator<?> it, int metaCount) {
            List<Object> buf = new ArrayList<>();
            while (it.hasNext()) {
                buf.add(it.next());
            }
            if (buf.size() == metaCount && buf.size() > 1) {
                buf = buf.subList(0, buf.size() - 1);
            }
            return buf.iterator();
        }

        private static void writeFrame(NodeSessionApi session, Object frame) {
            if (session == null || !(frame instanceof Map<?, ?>)) {
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> fm = (Map<String, Object>) frame;
                session.writeCustomStream(fm);
            } catch (RuntimeException ignored) {
                // mock
            }
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

        private record Prepared(Map<String, Object> fields, Map<String, Object> outputs) {}
    }
}
