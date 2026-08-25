/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AssembledWorkflow → executables (L2 {@code WorkflowAssemblyBridge}).
 * Prefer {@link #executeLinear} so {@link WorkflowVariableScope} is closed when the workflow ends.
 *
 * @since 2026-08-17
 */
public final class WorkflowAssemblyBridge {
    private final NodeTypeRegistry registry;
    /**
     * WorkflowAssemblyBridge.
     * @param registry registry
     */
    public WorkflowAssemblyBridge(NodeTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Map assembled nodes to executables (edges / scheduling remain host responsibility).
     */
    public Map<String, ComponentExecutable> mapExecutables(AssembledWorkflow workflow, NodeBuildContext ctx) {
        Map<String, ComponentExecutable> map = new LinkedHashMap<>();
        for (AssembledNode node : workflow.nodes()) {
            map.put(node.id(), registry.create(node, ctx));
        }
        return map;
    }

    /**
     * Sequential host smoke path: invoke nodes in declaration order, then close variable scope (L2 §3.7).
     * Nested child scopes are independent; only the root context passed here is closed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeLinear(
            AssembledWorkflow workflow,
            NodeBuildContext ctx,
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext modelContext) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(ctx, "ctx");
        try {
            Map<String, Object> current =
                    DeepCopies.map(inputs == null ? Map.of() : inputs);
            if (!current.containsKey("userFields")) {
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("userFields", new LinkedHashMap<>(current));
                current = wrap;
            }
            for (AssembledNode node : workflow.nodes()) {
                ComponentExecutable exec = registry.create(node, ctx);
                Object out = exec.invoke(current, session, modelContext);
                if (out instanceof Map<?, ?> m) {
                    Map<String, Object> next = new LinkedHashMap<>();
                    m.forEach((k, v) -> next.put(String.valueOf(k), v));
                    current = DeepCopies.map(next);
                }
            }
            return current;
        } finally {
            ctx.variableScope().close();
        }
    }
}
