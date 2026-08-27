/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.testsupport;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only linear workflow runner (not FEAT-031 / not production host orchestration).
 *
 * <p>Invokes nodes in {@link AssembledWorkflow} declaration order and merges {@code userFields}.
 * Production scheduling, IR binding, and edge routing remain the host responsibility.
 *
 * @since 0.1.0 (2026)
 */

public final class LinearWorkflowTestSupport {
    private LinearWorkflowTestSupport() {}

    /**
     * * Sequential smoke path for unit tests; closes {@link NodeBuildContext#variableScope()} on exit.
     *
     * @param registry registry
     * @param workflow workflow
     * @param ctx ctx
     * @param inputs inputs
     * @param session session
     * @param modelContext modelContext
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> executeLinear(
            NodeTypeRegistry registry,
            AssembledWorkflow workflow,
            NodeBuildContext ctx,
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext modelContext) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(ctx, "ctx");
        try {
            Map<String, Object> current = DeepCopies.map(inputs == null ? Map.of() : inputs);
            if (!current.containsKey("userFields")) {
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("userFields", new LinkedHashMap<>(current));
                current = wrap;
            }
            for (AssembledNode node : workflow.nodes()) {
                ComponentExecutable exec = registry.create(node, ctx);
                Object out = exec.invoke(current, session, modelContext);
                if (out instanceof Map<?, ?> m) {
                    Map<String, Object> produced = new LinkedHashMap<>();
                    m.forEach((k, v) -> produced.put(String.valueOf(k), v));
                    current = DeepCopies.map(
                            WorkflowAssemblyBridge.mergeLinearStep(current, produced, ctx.variableScope()));
                }
            }
            return current;
        } finally {
            ctx.variableScope().close();
        }
    }
}
