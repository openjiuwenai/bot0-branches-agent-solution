/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.message — Python flow_message (template required, stream frames, enable_history).
 *
 * @since 2026-08-17
 */
public final class MessageNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.message";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        Object tpl = node.configs() == null ? null : node.configs().get("template");
        if (tpl == null || String.valueOf(tpl).isBlank()) {
            // allow legacy keys as template stand-in only when template key absent
            boolean hasLegacy =
                    node.configs() != null
                            && (nonBlank(node.configs().get("message"))
                                    || nonBlank(node.configs().get("content"))
                                    || nonBlank(node.configs().get("text")));
            if (!hasLegacy) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.message",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "conf.template is required and must be non-empty");
            }
        }
        return new MessageExecutable(node);
    }

    private static boolean nonBlank(Object v) {
        return v != null && !String.valueOf(v).isBlank();
    }

    static final class MessageExecutable extends AbstractStudioNode {
        MessageExecutable(AssembledNode node) {
            super(node);
        }

        boolean endInterrupt() {
            Object event = node.configs().get("event");
            return event instanceof Map<?, ?> em && "task_completion".equals(String.valueOf(em.get("type")));
        }

        static boolean normalizeEnableHistory(Object value) {
            if (value == null) {
                return true;
            }
            if (value instanceof String s) {
                return !"false".equalsIgnoreCase(s.trim());
            }
            if (value instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = userFieldsOf(inputs);
            String template = resolveTemplate(uf);
            String rendered = TemplateRenderer.render(template, uf);
            boolean enableHistory = normalizeEnableHistory(node.configs().get("enable_history"));
            boolean struct =
                    Boolean.TRUE.equals(node.configs().get("isStructMessage"))
                            || Boolean.TRUE.equals(node.configs().get("enable_struct_message"));
            Object structTpl = node.configs().get("struct_output_template");
            String structAnswer = null;
            if (struct && structTpl != null && !String.valueOf(structTpl).isBlank()) {
                Map<String, Object> vars = new LinkedHashMap<>(uf);
                vars.put("_NODE_OUTPUT", rendered);
                structAnswer = TemplateRenderer.render(String.valueOf(structTpl), vars);
            }

            Map<String, Object> out = new LinkedHashMap<>(uf);
            out.put("result", rendered);
            out.put("answer", structAnswer != null ? structAnswer : rendered);
            out.put("enable_history", enableHistory);
            out.put("should_interrupt", endInterrupt());
            if (structAnswer != null) {
                out.put("struct_answer", structAnswer);
                out.put("is_struct_message", true);
                out.put("origin_answer", rendered);
            }

            List<Map<String, Object>> outputs = new ArrayList<>();
            Map<String, Object> rec = buildMessageOutputRecord(session, rendered);
            outputs.add(rec);
            out.put("message_outputs", outputs);
            appendWorkflowMessageOutputs(session, rec);

            Map<String, Object> streamData = baseStreamData(rendered, enableHistory);
            Object outputMode = node.configs().getOrDefault("outputMode", node.configs().get("output_mode"));
            if (outputMode != null) {
                streamData.put("output_mode", outputMode);
            }
            if (structAnswer != null) {
                Map<String, Object> endData = new LinkedHashMap<>(streamData);
                endData.put("answer", structAnswer);
                endData.put("result", rendered);
                endData.put("struct_answer", structAnswer);
                endData.put("origin_answer", rendered);
                endData.put("is_struct_message", true);
                emitPartialThenEnd(session, streamData, endData);
            } else {
                StudioStreamFrames.emitPartialAndMessageEnd(session, streamData);
            }
            emitLegacy(session, rendered, rec);

            List<MediaPart> media = PassthroughStudioNode.extractMedia(inputs);
            return NodePayload.userFields(out).withMediaPassthrough(media);
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> in = asMap(inputs);
            Map<String, Object> uf = userFieldsOf(in);
            String template = resolveTemplate(uf);
            String rendered = TemplateRenderer.render(template, uf);
            boolean enableHistory = normalizeEnableHistory(node.configs().get("enable_history"));
            boolean struct =
                    Boolean.TRUE.equals(node.configs().get("isStructMessage"))
                            || Boolean.TRUE.equals(node.configs().get("enable_struct_message"));
            Object structTpl = node.configs().get("struct_output_template");
            String structAnswer = null;
            if (struct && structTpl != null && !String.valueOf(structTpl).isBlank()) {
                Map<String, Object> vars = new LinkedHashMap<>(uf);
                vars.put("_NODE_OUTPUT", rendered);
                structAnswer = TemplateRenderer.render(String.valueOf(structTpl), vars);
            }

            List<Object> frames = new ArrayList<>();
            Map<String, Object> partial = baseStreamData(rendered, enableHistory);
            frames.add(Map.of("type", StudioStreamFrames.PARTIAL_CONTENT, "index", 0, "data", partial));
            Map<String, Object> end = new LinkedHashMap<>(partial);
            end.put("result", rendered);
            end.put("enable_history", enableHistory);
            if (structAnswer != null) {
                end.put("struct_answer", structAnswer);
                end.put("is_struct_message", true);
                end.put("origin_answer", rendered);
                end.put("answer", structAnswer);
            }
            frames.add(Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", end));

            if (session != null) {
                for (Object f : frames) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fm = (Map<String, Object>) f;
                        session.writeCustomStream(fm);
                    } catch (RuntimeException ignored) {
                        // mock
                    }
                }
            }
            return frames.iterator();
        }

        private String resolveTemplate(Map<String, Object> uf) {
            Object tpl = node.configs().get("template");
            if (tpl != null && !String.valueOf(tpl).isBlank()) {
                return String.valueOf(tpl);
            }
            return firstString(
                    node.configs().get("message"),
                    node.configs().get("content"),
                    node.configs().get("text"),
                    uf.get("message"));
        }

        private Map<String, Object> baseStreamData(String rendered, boolean enableHistory) {
            Map<String, Object> streamData = new LinkedHashMap<>();
            streamData.put("answer", rendered);
            streamData.put("result", rendered);
            streamData.put("node_id", node.id());
            streamData.put("node_type", "jiuwen.message");
            streamData.put("should_interrupt", endInterrupt());
            streamData.put("enable_history", enableHistory);
            return streamData;
        }

        private Map<String, Object> buildMessageOutputRecord(NodeSessionApi session, String rendered) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("node_type", "message");
            rec.put("output", rendered);
            rec.put("result", rendered);
            rec.put("componentType", "message");
            rec.put("nodeId", node.id());
            rec.put("message_component_id", node.id());
            rec.put("time_stamp", System.currentTimeMillis());
            Object outputMode = node.configs().getOrDefault("outputMode", node.configs().get("output_mode"));
            if (outputMode != null) {
                rec.put("outputMode", outputMode);
            }
            if (session != null) {
                try {
                    String wf = session.getWorkflowId();
                    if (wf != null) {
                        rec.put("host_workflow_id", wf);
                        rec.put("root_workflow_id", wf);
                    }
                } catch (RuntimeException ignored) {
                    // mock
                }
                try {
                    Object exec = session.getExecutableId();
                    if (exec != null) {
                        rec.put("executable_id", String.valueOf(exec));
                    } else {
                        rec.put("executable_id", node.id());
                    }
                } catch (RuntimeException ignored) {
                    rec.put("executable_id", node.id());
                }
            } else {
                rec.put("executable_id", node.id());
            }
            rec.putIfAbsent("workflow_nesting_depth", 0);
            return rec;
        }

        @SuppressWarnings("unchecked")
        private void appendWorkflowMessageOutputs(NodeSessionApi session, Map<String, Object> rec) {
            if (session == null) {
                return;
            }
            try {
                Object existing = session.getGlobalState("message_outputs");
                List<Object> list = new ArrayList<>();
                if (existing instanceof List<?> l) {
                    list.addAll(l);
                }
                list.add(rec);
                session.updateGlobalState(Map.of("message_outputs", list));
            } catch (RuntimeException ignored) {
                // mock
            }
        }

        private void emitPartialThenEnd(
                NodeSessionApi session, Map<String, Object> partial, Map<String, Object> end) {
            if (session == null) {
                return;
            }
            try {
                session.writeCustomStream(
                        Map.of("type", StudioStreamFrames.PARTIAL_CONTENT, "index", 0, "data", partial));
                session.writeCustomStream(
                        Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", end));
            } catch (RuntimeException ignored) {
                // mock
            }
        }

        private void emitLegacy(NodeSessionApi session, String rendered, Map<String, Object> rec) {
            if (session == null) {
                return;
            }
            try {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("type", "jiuwen.message");
                frame.put("event", "message");
                frame.put("content", rendered);
                frame.put("payload", rec);
                session.writeCustomStream(frame);
            } catch (RuntimeException ignored) {
                try {
                    session.writeStream(rendered);
                } catch (RuntimeException ignored2) {
                    // mock
                }
            }
        }

        private static String firstString(Object... candidates) {
            for (Object c : candidates) {
                if (c != null && !String.valueOf(c).isBlank()) {
                    return String.valueOf(c);
                }
            }
            return "";
        }
    }
}
