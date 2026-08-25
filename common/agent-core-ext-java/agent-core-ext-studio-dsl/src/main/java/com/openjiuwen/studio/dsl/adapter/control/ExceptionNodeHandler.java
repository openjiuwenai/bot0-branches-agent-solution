/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.exception — Python ExceptionInfo (abort + workflow_exception) / soft defaultOutputs.
 *
 * @since 2026-08-17
 */
public final class ExceptionNodeHandler implements NodeHandlerFactory {
    static final String WORKFLOW_EXCEPTION = "workflow_exception";
    static final String ABORT_FLAG = "__abort__";

    @Override
    public String canonicalType() {
        return "jiuwen.exception";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new ExceptionExecutable(node);
    }

    static final class ExceptionExecutable extends AbstractStudioNode {
        ExceptionExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Map<String, Object> ex = new LinkedHashMap<>();
            Object incoming = inputs.get("exception");
            if (incoming instanceof Map<?, ?> m) {
                m.forEach((k, v) -> ex.put(String.valueOf(k), v));
            } else if (uf.containsKey("exception")) {
                Object e = uf.get("exception");
                if (e instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> ex.put(String.valueOf(k), v));
                } else {
                    ex.put("message", String.valueOf(e));
                }
            } else {
                ex.put("message", uf.getOrDefault("error", uf.getOrDefault("message", "exception branch")));
            }
            Object code = node.configs().getOrDefault("errorCode", node.configs().get("error_code"));
            if (code != null) {
                ex.putIfAbsent("errorCode", code);
            }
            uf.put("exception", ex);
            uf.put("jiuwen_exception_node_id", node.id());

            String handleType =
                    String.valueOf(
                                    node.configs()
                                            .getOrDefault(
                                                    "handleType",
                                                    node.configs().getOrDefault("handle_type", "")))
                            .toLowerCase();
            boolean soft =
                    "default".equals(handleType)
                            || "continue".equals(handleType)
                            || "soft".equals(handleType)
                            || "default_outputs".equals(handleType);
            if (soft) {
                Object defaults =
                        node.configs().getOrDefault("defaultOutputs", node.configs().get("default_outputs"));
                if (defaults instanceof Map<?, ?> dm) {
                    dm.forEach((k, v) -> uf.putIfAbsent(String.valueOf(k), v));
                }
                return NodePayload.userFields(uf);
            }

            // Python ExceptionInfo: first abort emits workflow_exception; concurrent aborts skip frame
            if (session != null) {
                try {
                    boolean alreadyAborted = session.getGlobalState(ABORT_FLAG) != null;
                    if (!alreadyAborted) {
                        session.updateGlobalState(Map.of(ABORT_FLAG, true));
                        Map<String, Object> frameData = new LinkedHashMap<>(uf);
                        frameData.put("jiuwen_exception_node_id", node.id());
                        session.writeCustomStream(
                                Map.of("type", WORKFLOW_EXCEPTION, "index", 0, "data", frameData));
                    }
                } catch (RuntimeException ignored) {
                    // mock
                }
            }
            throw new NodeExecutionException(
                    node.id(),
                    node.canonicalType(),
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    String.valueOf(ex.getOrDefault("message", "exception abort")));
        }
    }
}
