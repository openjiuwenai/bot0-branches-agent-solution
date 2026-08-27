/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowcard;

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
 * Python {@code FlowCard} — template render, struct output, card_outputs, stream/collect/transform.
 *
 * @since 2026-08-26
 */

public final class FlowCardEngine {
    public static final String CARD_OUTPUTS_KEY = "card_outputs";
    public static final String USER_FIELDS = "userFields";
    public static final String NODE_TYPE = "card";
    public static final String JIUWEN_CARD_TYPE = "jiuwen.card";

    private static final ZoneId BJ_ZONE = ZoneId.of("Asia/Shanghai");

    private final String nodeId;
    private final FlowCardConfig config;

    public FlowCardEngine(String nodeId, FlowCardConfig config) {
        this.nodeId = nodeId;
        this.config = config;
    }

    /**
     * Batch invoke — Python {@code FlowCard.invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @return map with at least {@code result}
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        try {
            Map<String, Object> uf = extractUserFields(inputs);
            String rendered = renderTemplate(uf);
            collectCardOutput(rendered, session);
            return Map.of("result", rendered);
        } catch (RuntimeException e) {
            return Map.of("result", config.template());
        }
    }

    /**
     * Stream — Python {@code FlowCard.stream} (chunked + partial/message_end).
     *
     * @param inputs inputs
     * @param session session
     * @return frame iterator
     */

    public Iterator<Object> stream(Map<String, Object> inputs, NodeSessionApi session) {
        List<Object> frames = new ArrayList<>();
        try {
            Map<String, Object> uf = extractUserFields(inputs);
            String rendered = renderTemplate(uf);
            String finalOutput = formatStructuredOutput(rendered, uf);

            for (int i = 0; i < finalOutput.length(); i++) {
                int idx = i;
                frames.add(
                        Map.of(
                                "type",
                                NODE_TYPE,
                                "index",
                                idx,
                                "payload",
                                Map.of("response", String.valueOf(finalOutput.charAt(i)))));
            }

            collectCardOutput(rendered, session);
            Map<String, Object> custom = streamCustomData(rendered, session);
            if (session != null) {
                writeStream(session, streamFrame(StudioStreamFrames.PARTIAL_CONTENT, 0, custom));
                writeStream(session, streamFrame(StudioStreamFrames.MESSAGE_NODE_END, 0, custom));
            }
        } catch (RuntimeException e) {
            frames.add(
                    Map.of(
                            "type",
                            NODE_TYPE,
                            "index",
                            0,
                            "payload",
                            Map.of("response", config.template())));
        }
        return frames.iterator();
    }

    /**
     * collect — Python {@code FlowCard.collect}.
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
     * transform — Python {@code FlowCard.transform}.
     *
     * @param inputs inputs
     * @param session session
     * @return stream iterator
     */

    public Iterator<Object> transform(Object inputs, NodeSessionApi session) {
        Map<String, Object> resolved = FlowStreamTransformEngine.resolveStreamInputs(inputs);
        return stream(resolved, session);
    }

    public Map<String, Object> formatCardOutputRecord(String output) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("node_type", NODE_TYPE);
        rec.put("output", output);
        rec.put(
                "time_stamp",
                ZonedDateTime.now(BJ_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return rec;
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
            return config.template();
        }
    }

    /**
     * formatStructuredOutput.
     *
     * @param originOutput originOutput
     * @param variables variables
     * @return result
     * @since 0.1.0
     */

    public String formatStructuredOutput(String originOutput, Map<String, Object> variables) {
        if (!config.enableStructMessage() || config.structOutputTemplate().isBlank()) {
        return originOutput;
    }
        try {
            Map<String, Object> all = new LinkedHashMap<>(variables == null ? Map.of() : variables);
            all.put("_NODE_OUTPUT", originOutput);
            return TemplateRenderer.render(config.structOutputTemplate(), all);
        } catch (RuntimeException e) {
            return originOutput;
        }
    }

    void collectCardOutput(String output, NodeSessionApi session) {
        if (session == null) {
        return;
    }
        try {
            Object existing = session.getGlobalState(CARD_OUTPUTS_KEY);
            List<Object> list = new ArrayList<>();
            if (existing instanceof List<?> l) {
                list.addAll(l);
            }
            list.add(formatCardOutputRecord(output));
            session.updateGlobalState(Map.of(CARD_OUTPUTS_KEY, list));
        } catch (RuntimeException ignored) {
            // mock session
        }
    }

    private Map<String, Object> streamCustomData(String rendered, NodeSessionApi session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", rendered);
        data.put("result", rendered);
        data.put("node_id", nodeId);
        data.put("node_name", config.name());
        data.put("node_type", JIUWEN_CARD_TYPE);
        data.put("should_interrupt", false);
        return data;
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
        try {
            session.writeCustomStream((Map<String, Object>) frame);
        } catch (RuntimeException ignored) {
            // mock
        }
    }
}
