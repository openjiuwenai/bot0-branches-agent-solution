/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowapi.FlowApiEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.util.MediaSupport;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.plugin / api / flowApi — strict 1:1 with Python {@code flow_api.FlowApi}.
 *
 * <p>Thin adapter; behaviour lives in {@link FlowApiEngine}.
 *
 * @since 2026-08-17
 */
public final class PluginNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.plugin";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.api", "jiuwen.flowApi");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new PluginExecutable(node, ctx == null ? null : ctx.toolRegistry());
    }

    /**
     * Unwrap FlowApi-style {@code errCode}/{@code data} envelopes (Python {@code _format_api_outputs}).
     *
     * @param outputs raw plugin/tool/mock output
     * @return data map or passthrough
     */
    public static Map<String, Object> formatApiOutputs(Object outputs) {
        return FlowApiEngine.formatApiOutputsStatic(outputs);
    }

    static final class PluginExecutable extends AbstractStudioNode {
        private final FlowApiEngine engine;
        private final Map<String, Object> nodeConfigs;
        private final ToolRegistry toolRegistry;
        private volatile boolean ready;

        PluginExecutable(AssembledNode node, ToolRegistry toolRegistry) {
            super(node);
            this.engine = new FlowApiEngine(node.id());
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
            this.toolRegistry = toolRegistry;
        }

        FlowApiEngine engine() {
            return engine;
        }

        private void ensureInit() {
            if (ready) {
                return;
            }
            synchronized (this) {
                if (!ready) {
                    engine.setToolRegistry(toolRegistry);
                    engine.init(new LinkedHashMap<>(nodeConfigs));
                    ready = true;
                }
            }
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            ensureInit();
            List<MediaPart> media = MediaSupport.mediaOf(inputs);
            Map<String, Object> in = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
            if (!media.isEmpty()) {
                Map<String, Object> uf = MediaSupport.withConsumableMedia(userFieldsOf(in), media);
                in.put(FlowApiEngine.USER_FIELDS, uf);
            }
            Map<String, Object> result = engine.invoke(in, session, context);
            return NodePayload.ofFields(result).withMediaPassthrough(media);
        }

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            ensureInit();
            return engine.stream(inputs, session, context);
        }
    }
}
