/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowcode.FlowCodeEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.code — strict 1:1 with Python {@code agent_runtime...flow_code.FlowCode}.
 *
 * <p>Thin adapter; all behaviour in {@link FlowCodeEngine} (Python runners only).
 *
 * @since 2026-08-17
 */
public final class CodeNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.code";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new CodeExecutable(node, ctx);
    }

    static final class CodeExecutable extends AbstractStudioNode {
        private final NodeBuildContext ctx;
        private final FlowCodeEngine engine;
        private final Map<String, Object> nodeConfigs;
        private volatile boolean ready;

        CodeExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            this.ctx = ctx;
            this.engine = new FlowCodeEngine(node.id());
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
        }

        FlowCodeEngine engine() {
            return engine;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            ensureInit();
            return NodePayload.ofFields(engine.invoke(inputs, session, context));
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            ensureInit();
            return engine.stream(inputs, session, context);
        }

        private void ensureInit() {
            if (ready) {
                return;
            }
            synchronized (this) {
                if (!ready) {
                    String language = stringVal(nodeConfigs.get("language"));
                    if (!language.isBlank() && !"python".equalsIgnoreCase(language)) {
                        throw new NodeExecutionException(
                                node.id(),
                                "jiuwen.code",
                                NodeCauseCode.NODE_CONFIG_INVALID,
                                "unsupported language=" + language + " (only python)");
                    }
                    StudioDslNodeProperties props = ctx == null ? null : ctx.properties();
                    engine.setBuildContext(
                            props,
                            ctx == null ? null : ctx.pythonExecutor(),
                            ctx == null ? null : ctx.tenantId(),
                            ctx == null ? null : ctx.workflowId());
                    engine.init(new LinkedHashMap<>(nodeConfigs));
                    ready = true;
                }
            }
        }

        private static String stringVal(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
