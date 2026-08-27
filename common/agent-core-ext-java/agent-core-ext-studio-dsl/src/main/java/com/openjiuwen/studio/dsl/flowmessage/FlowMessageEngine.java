/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmessage;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.flowstreamtransform.FlowStreamTransformEngine;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code Message} / {@code flow_message.py} — template render, message_outputs, stream protocol.
 *
 * @since 2026-08-26
 */

public final class FlowMessageEngine {

    /**
     * MESSAGE_OUTPUTS_KEY.
     *
     * @since 0.1.0
     */

    public static final String MESSAGE_OUTPUTS_KEY = "message_outputs";

    /**
     * USER_FIELDS.
     *
     * @since 0.1.0
     */

    public static final String USER_FIELDS = "userFields";

    /**
     * NODE_TYPE.
     *
     * @since 0.1.0
     */

    public static final String NODE_TYPE = "message";

    /**
     * JIUWEN_MESSAGE_TYPE.
     *
     * @since 0.1.0
     */

    public static final String JIUWEN_MESSAGE_TYPE = "jiuwen.message";

    /**
     * * * Python {@code MESSAGE_NODE_STREAM}.
     */
    public static final String MESSAGE_NODE_STREAM = "message node stream";

    private static final ZoneId BJ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SPLIT_DELIMITER = "\u0001";

    private final String nodeId;
    private final FlowMessageConfig config;

    /**
     * FlowMessageEngine.
     *
     * @param nodeId nodeId
     * @param config config
     * @since 0.1.0
     */

    public FlowMessageEngine(String nodeId, FlowMessageConfig config) {
        this.nodeId = nodeId;
        this.config = config;
    }

    /**
     * Batch invoke — Python {@code Message.invoke}. Returns {@code {"result": final_res}} only.
     *
     * @param inputs inputs
     * @param session session
     * @return map with {@code result}
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> uf = extractUserFields(inputs);
        String finalRes = renderTemplate(uf);
        Map<String, Object> endPayload = buildEndPayload(finalRes, uf);

        if (session != null) {
            Map<String, Object> partial = basePartialData(finalRes);
            writeStream(session, streamFrame(StudioStreamFrames.PARTIAL_CONTENT, 0, partial));
            writeStream(session, streamFrame(StudioStreamFrames.MESSAGE_NODE_END, 1, endPayload));
        }
        appendWorkflowMessageOutput(session, messageOutputRecord(finalRes, session));
        return Map.of("result", finalRes);
    }

    /**
     * Stream — Python {@code Message.stream}. Yields MESSAGE_NODE_STREAM + MESSAGE_NODE_END; does not
     * append {@code message_outputs}.
     *
     * @param inputs inputs
     * @param session session
     * @return frame iterator
     */

    public Iterator<Object> stream(Map<String, Object> inputs, NodeSessionApi session) {
        List<Object> frames = new ArrayList<>();
        Map<String, Object> uf = extractUserFields(inputs == null ? Map.of() : inputs);
        String finalRes = renderTemplate(uf);

        // No TemplateProcessor.render_stream: emit one progressive piece (full text) then end.
        Map<String, Object> streamPayload = new LinkedHashMap<>();
        streamPayload.put("result", finalRes);
        frames.add(outputSchema(MESSAGE_NODE_STREAM, 0, streamPayload));

        Map<String, Object> partial = basePartialData(finalRes);
        writeStream(session, streamFrame(StudioStreamFrames.PARTIAL_CONTENT, 0, partial));

        Map<String, Object> endPayload = buildEndPayload(finalRes, uf);
        frames.add(outputSchema(StudioStreamFrames.MESSAGE_NODE_END, 1, endPayload));
        writeStream(session, streamFrame(StudioStreamFrames.MESSAGE_NODE_END, 1, endPayload));
        return frames.iterator();
    }

    /**
     * collect — Python {@code Message.collect} (preserve_generators ≈ resolve then invoke).
     *
     * @param inputs inputs
     * @param session session
     * @return invoke map
     */

    public Map<String, Object> collect(Object inputs, NodeSessionApi session) {
        Map<String, Object> resolved = FlowStreamTransformEngine.resolveStreamInputs(inputs);
        return invoke(resolved, session);
    }

    /**
     * transform — Python {@code Message.transform}.
     *
     * @param inputs inputs
     * @param session session
     * @return stream iterator
     */

    public Iterator<Object> transform(Object inputs, NodeSessionApi session) {
        Map<String, Object> resolved = FlowStreamTransformEngine.resolveStreamInputs(inputs);
        return stream(resolved, session);
    }

    public Map<String, Object> extractUserFields(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    String renderTemplate(Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        try {
            return TemplateRenderer.render(config.template(), vars);
        } catch (RuntimeException e) {
            throw new com.openjiuwen.studio.dsl.exec.NodeExecutionException(
                    nodeId,
                    NODE_TYPE,
                    com.openjiuwen.studio.dsl.model.NodeCauseCode.NODE_INVOKE_FAILED,
                    "message template render failed: " + e.getMessage(),
                    e);
        }
    }

    /**
     * formatStructAnswer.
     *
     * @param originOutput originOutput
     * @param variables variables
     * @return result
     * @since 0.1.0
     */

    public String formatStructAnswer(String originOutput, Map<String, Object> variables) {
        if (!config.enableStructMessage() || config.structOutputTemplate().isBlank()) {
        return "";
    }
        try {
            Map<String, Object> all = new LinkedHashMap<>(variables == null ? Map.of() : variables);
            all.put("_NODE_OUTPUT", originOutput);
            return TemplateRenderer.render(config.structOutputTemplate(), all);
        } catch (RuntimeException e) {
            throw new com.openjiuwen.studio.dsl.exec.NodeExecutionException(
                    nodeId,
                    NODE_TYPE,
                    com.openjiuwen.studio.dsl.model.NodeCauseCode.NODE_INVOKE_FAILED,
                    "struct message template render failed: " + e.getMessage(),
                    e);
        }
    }

    /**
     * splitAnswerThink.
     *
     * @param answer answer
     * @return result
     * @since 0.1.0
     */

    public static String[] splitAnswerThink(String answer) {
        if (answer == null) {
        return new String[] {"", ""};
    }
        int i = answer.indexOf(SPLIT_DELIMITER);
        if (i < 0) {
            return new String[] {answer, ""};
        }
        return new String[] {answer.substring(0, i), answer.substring(i + 1)};
    }

    public Map<String, Object> messageOutputRecord(String output, NodeSessionApi session) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("node_type", NODE_TYPE);
        rec.put("output", output);
        rec.put(
                "time_stamp",
                ZonedDateTime.now(BJ_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        rec.putAll(workflowMessageScope(session));
        return rec;
    }

    Map<String, Object> workflowMessageScope(NodeSessionApi session) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("message_component_id", nodeId);
        if (session == null) {
            scope.put("executable_id", nodeId);
            return scope;
        }
        try {
            String wf = session.getWorkflowId();
            if (wf != null) {
                scope.put("host_workflow_id", wf);
                scope.put("root_workflow_id", wf);
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
        try {
            Object exec = session.getExecutableId();
            scope.put("executable_id", exec != null ? String.valueOf(exec) : nodeId);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            scope.put("executable_id", nodeId);
        }
        scope.putIfAbsent("workflow_nesting_depth", 0);
        return scope;
    }

    void appendWorkflowMessageOutput(NodeSessionApi session, Map<String, Object> record) {
        if (session == null) {
        return;
    }
        try {
            Object existing = session.getGlobalState(MESSAGE_OUTPUTS_KEY);
            List<Object> list = new ArrayList<>();
            if (existing instanceof List<?> l) {
                list.addAll(l);
            }
            list.add(record);
            session.updateGlobalState(Map.of(MESSAGE_OUTPUTS_KEY, list));
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
    }

    private Map<String, Object> buildEndPayload(String finalRes, Map<String, Object> uf) {
        String structAnswer = formatStructAnswer(finalRes, uf);
        String[] parts = splitAnswerThink(finalRes);
        String answer = parts[0];
        String think = parts[1];
        String nodeName = config.name() != null ? config.name() : nodeId;

        Map<String, Object> end = new LinkedHashMap<>();
        end.put("result", finalRes);
        end.put("answer", structAnswer.isEmpty() ? answer : structAnswer);
        end.put("think", think);
        end.put("enable_history", config.enableHistory());
        end.put("output_mode", config.outputMode());
        end.put("origin_answer", answer);
        end.put("is_struct_message", !structAnswer.isEmpty());
        end.put("node_id", nodeId);
        end.put("node_name", nodeName);
        end.put("node_type", JIUWEN_MESSAGE_TYPE);
        end.put("should_interrupt", config.endInterrupt());
        return end;
    }

    private Map<String, Object> basePartialData(String piece) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", piece);
        data.put("result", piece);
        data.put("node_id", nodeId);
        data.put("node_name", config.name() != null ? config.name() : nodeId);
        data.put("node_type", JIUWEN_MESSAGE_TYPE);
        data.put("should_interrupt", config.endInterrupt());
        return data;
    }

    private static Map<String, Object> outputSchema(String type, int index, Map<String, Object> payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("index", index);
        frame.put("payload", payload);
        return frame;
    }

    private static Map<String, Object> streamFrame(String type, int index, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("index", index);
        frame.put("data", data);
        return frame;
    }

    @SuppressWarnings("unchecked")
    private static void writeStream(NodeSessionApi session, Map<?, ?> frame) {
        if (session == null) {
        return;
    }
        try {
            session.writeCustomStream((Map<String, Object>) frame);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
    }
}
