/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowinput.FlowInputEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.input — strict 1:1 with Python {@code jiuwen/extension/workflow_node/flow_input.py}.
 *
 * @since 2026-08-17
 */
public final class InputNodeHandler implements NodeHandlerFactory {
    /** Python FLOW_INPUT_STATE_KEY. */
    public static final String STATE_KEY = FlowInputEngine.STATE_KEY;

    @Override
    public String canonicalType() {
        return "jiuwen.input";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowInput");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new InputExecutable(node);
    }

    static final class InputExecutable extends AbstractStudioNode {
        private final FlowInputEngine engine;

        InputExecutable(AssembledNode node) {
            super(node);
            Map<String, Object> conf = new LinkedHashMap<>();
            if (node.configs() != null) {
                conf.putAll(node.configs());
            }
            this.engine = new FlowInputEngine(node.id(), conf);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> out = engine.invoke(inputs, session);
            return NodePayload.ofFields(out);
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.stream(inputs, session);
        }
    }
}
