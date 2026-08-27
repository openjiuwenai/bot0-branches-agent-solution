/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.components.flow.loop.LoopComponent;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.loop — Studio IR → core {@link LoopComponent} + {@link com.openjiuwen.core.workflow.component.loop.LoopGroup}
 * (same assembly path as Python {@code test_loop_component.py}).
 *
 * @since 2026-08-17
 */

public final class LoopNodeHandler implements NodeHandlerFactory {
    private final NodeTypeRegistry registry;

    /**
     * LoopNodeHandler.
     *
     * @param registry registry
     */

    public LoopNodeHandler(NodeTypeRegistry registry) {
        this.registry = registry;
    }

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.loop";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     * @since 0.1.0
     */

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new LoopExecutable(node, registry, ctx);
    }
    static final class LoopExecutable extends AbstractStudioNode {
        private final NodeTypeRegistry registry;
        private final NodeBuildContext ctx;
        private final AtomicReference<Map<String, Object>> frame = new AtomicReference<>(new LinkedHashMap<>());

        LoopExecutable(AssembledNode node, NodeTypeRegistry registry, NodeBuildContext ctx) {
            super(node);
            this.registry = registry;
            this.ctx = ctx;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Map<String, Object> configs = node.configs() == null ? Map.of() : node.configs();

            // Seed shared frame (intermediate_var + inbound userFields) before LoopGroup runs
            Map<String, Object> seed = new LinkedHashMap<>(uf);
            Object intermediate =
                    configs.getOrDefault("intermediate_var", configs.get("intermediateVar"));
            if (intermediate instanceof Map<?, ?> m) {
                m.forEach((k, v) -> seed.putIfAbsent(String.valueOf(k), v));
            }
            frame.set(seed);

            StudioLoopGroupAssembler.AssembledLoop assembled =
                    StudioLoopGroupAssembler.assemble(node, registry, ctx, frame);
            LoopComponent loop = assembled.component();

            Map<String, Object> loopInputs = StudioLoopGroupAssembler.buildLoopInputs(configs, seed);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(Constant.INPUTS_KEY, loopInputs);
            wrapped.put(Constant.CONFIG_KEY, Map.of());

            NodeSessionApi loopSession = ensureSession(session, node.id());
            Object raw = loop.invoke(wrapped, loopSession, context);

            Map<String, Object> outUf = new LinkedHashMap<>(seed);
            Map<String, Object> latest = frame.get();
            if (latest != null) {
                outUf.putAll(latest);
            }
            mergeLoopResult(outUf, raw, assembled.outputSchema());
            // Studio IR convenience keys
            Object collected = outUf.get("text");
            if (collected instanceof List<?> list) {
                outUf.putIfAbsent("loopCount", list.size());
                List<Object> iterations = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("index", i);
                    rec.put("bodyOutput", Map.of("result", list.get(i)));
                    iterations.add(rec);
                }
                outUf.putIfAbsent("loopOutputs", iterations);
            } else if (outUf.get("total") instanceof List<?> totals) {
                outUf.putIfAbsent("loopCount", totals.size());
            }
            if (outUf.get("loopCount") == null) {
                Object n = loopInputs.get("loop_number");
                if (n instanceof Number num) {
                    outUf.put("loopCount", num.intValue());
                } else if (loopInputs.get("loop_array") instanceof Map<?, ?> am) {
                    int min = Integer.MAX_VALUE;
                    for (Object v : am.values()) {
                        if (v instanceof List<?> list) {
                            min = Math.min(min, list.size());
                        }
                    }
                    if (min != Integer.MAX_VALUE) {
                        outUf.put("loopCount", min);
                    }
                }
            }
            return NodePayload.userFields(outUf);
        }

        @SuppressWarnings("unchecked")
        private static void mergeLoopResult(
                Map<String, Object> outUf, Object raw, Map<String, Object> outputSchema) {
            if (raw == null) {
            return;
        }
            Map<String, Object> resultMap;
            if (raw instanceof Map<?, ?> m) {
                resultMap = new LinkedHashMap<>();
                m.forEach((k, v) -> resultMap.put(String.valueOf(k), v));
            } else {
                return;
            }
            // LoopComponent may nest under answer/output/result
            Object nested = resultMap.get("output");
            if (nested instanceof Map<?, ?> nm) {
                nm.forEach((k, v) -> outUf.put(String.valueOf(k), v));
            }
            Object answer = resultMap.get("answer");
            if (answer instanceof Map<?, ?> am) {
                Object output = am.get("output");
                if (output instanceof Map<?, ?> om) {
                    om.forEach((k, v) -> outUf.put(String.valueOf(k), v));
                } else {
                    am.forEach((k, v) -> outUf.put(String.valueOf(k), v));
                }
            }
            for (String key : outputSchema.keySet()) {
                if (resultMap.containsKey(key)) {
                    outUf.put(key, resultMap.get(key));
                }
            }
            // also copy any list-valued top-level keys
            resultMap.forEach((k, v) -> {
                if (v instanceof List || (outputSchema.containsKey(k) && !outUf.containsKey(k))) {
                    outUf.put(k, v);
                }
            });
        }

        private static NodeSessionApi ensureSession(NodeSessionApi session, String nodeId) {
            if (session != null) {
                try {
                    if (session.getInner() != null) {
            return session;
        }
                } catch (RuntimeException | Error ignored) {
                    // mock / incomplete session
                }
            }
            WorkflowSession wf =
                    new WorkflowSession("studio-loop-wf", null, null, InMemoryState.create(), null);
            return new NodeSessionApi(new NodeSession(wf, nodeId == null ? "loop" : nodeId));
        }
    }
}
