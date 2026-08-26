/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps {@link AssembledWorkflow} to {@link ComponentExecutable} and merges step outputs for nested
 * child invocation ({@code jiuwen.subWorkflow}). Not a workflow engine — scheduling and IR binding
 * are host responsibilities (FEAT-027 / agent-core-java {@code Workflow}).
 *
 * @since 2026-08-17
 */
public final class WorkflowAssemblyBridge {
    private final NodeTypeRegistry registry;

    /**
     * WorkflowAssemblyBridge.
     *
     * @param registry registry
     */
    public WorkflowAssemblyBridge(NodeTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Map assembled nodes to executables (edges / scheduling remain host responsibility).
     *
     * @param workflow workflow
     * @param ctx ctx
     * @return result
     */
    public Map<String, ComponentExecutable> mapExecutables(AssembledWorkflow workflow, NodeBuildContext ctx) {
        Map<String, ComponentExecutable> map = new LinkedHashMap<>();
        for (AssembledNode node : workflow.nodes()) {
            map.put(node.id(), registry.create(node, ctx));
        }
        return map;
    }

    /**
     * mergeLinearStep.
     *
     * @param current current envelope
     * @param produced node invoke result
     * @return merged envelope
     */
    public static Map<String, Object> mergeLinearStep(
            Map<String, Object> current, Map<String, Object> produced) {
        return mergeLinearStep(current, produced, null);
    }

    /**
     * mergeLinearStep with optional scope (setVariable writes here; Python set_outputs analogue).
     *
     * @param current current envelope
     * @param produced node invoke result
     * @param scope optional workflow variable scope
     * @return merged envelope
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeLinearStep(
            Map<String, Object> current, Map<String, Object> produced, WorkflowVariableScope scope) {
        Map<String, Object> prevUf = extractUserFields(current);
        Map<String, Object> nextUf = new LinkedHashMap<>(prevUf);
        Object producedUf = produced == null ? null : produced.get("userFields");
        if (producedUf instanceof Map<?, ?> um) {
            um.forEach((k, v) -> nextUf.put(String.valueOf(k), v));
        } else if (produced != null) {
            produced.forEach((k, v) -> {
                if (!"userFields".equals(k)) {
                    nextUf.put(k, v);
                }
            });
        }
        if (scope != null) {
            nextUf.putAll(scope.snapshot());
        }
        Map<String, Object> next = new LinkedHashMap<>(produced == null ? Map.of() : produced);
        next.put("userFields", nextUf);
        return next;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractUserFields(Map<String, Object> envelope) {
        if (envelope == null) {
            return new LinkedHashMap<>();
        }
        Object uf = envelope.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        envelope.forEach((k, v) -> {
            if (!"userFields".equals(k)) {
                flat.put(k, v);
            }
        });
        return flat;
    }
}
