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
import com.openjiuwen.studio.dsl.flowexception.FlowExceptionConfig;
import com.openjiuwen.studio.dsl.flowexception.FlowExceptionEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.exception — strict 1:1 with Python {@code flow_exception.py} ({@code ExceptionInfo}).
 *
 * @since 2026-08-17
 */
public final class ExceptionNodeHandler implements NodeHandlerFactory {
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
        Object name = node.configs() == null ? null : node.configs().get("name");
        FlowExceptionConfig config =
                new FlowExceptionConfig(
                        node.id(),
                        name == null ? null : String.valueOf(name),
                        "jiuwen.exception");
        return new ExceptionExecutable(node, config);
    }

    static final class ExceptionExecutable extends AbstractStudioNode {
        private final FlowExceptionEngine engine;

        ExceptionExecutable(AssembledNode node, FlowExceptionConfig config) {
            super(node);
            this.engine = new FlowExceptionEngine(config);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            engine.invoke(inputs, session);
            // unreachable — invoke always throws WorkflowAbortException
            return NodePayload.ofFields(Map.of());
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.stream(asMap(inputs), session);
        }
    }
}
