/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.WorkflowComponent;
import com.openjiuwen.core.workflow.components.flow.SubWorkflowComponent;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assemble Studio {@link AssembledWorkflow} into core {@link Workflow} + {@link SubWorkflowComponent}
 * (same shape as Python SubWorkflow wrapping a compiled child Workflow / Java
 * {@code WorkflowInterruptSystemTest} nested SubWorkflow).
 *
 * @since 2026-08-26
 */
final class StudioSubWorkflowAssembler {
    private StudioSubWorkflowAssembler() {}

    static AssembledSub assemble(
            AssembledWorkflow child,
            String nestNodeId,
            NodeTypeRegistry registry,
            NodeBuildContext ctx,
            AtomicReference<Map<String, Object>> frame) {
        String wfId = child.workflowId() == null || child.workflowId().isBlank()
                ? "studio-sub-" + (nestNodeId == null ? "anon" : nestNodeId)
                : child.workflowId();
        Workflow workflow = new Workflow(new WorkflowCard(wfId, wfId));
        List<AssembledNode> nodes = child.nodes() == null ? List.of() : child.nodes();
        List<String> ids = new ArrayList<>();
        for (AssembledNode n : nodes) {
            ids.add(n.id());
        }
        if (ids.isEmpty()) {
            // empty child: single passthrough end
            workflow.setEndComp(
                    "_studio_empty_end",
                    new StudioSubPassthroughAdapter(frame),
                    Map.of(),
                    null);
            return new AssembledSub(workflow, new SubWorkflowComponent(workflow), ids, frame);
        }

        if (nodes.size() == 1) {
            AssembledNode only = nodes.get(0);
            workflow.setStartComp(
                    "_studio_sub_in",
                    new StudioSubPassthroughAdapter(frame),
                    null,
                    null);
            workflow.setEndComp(only.id(), new StudioSubBodyAdapter(only, registry, ctx, frame), null, null);
            workflow.addConnection("_studio_sub_in", only.id());
        } else {
            for (int i = 0; i < nodes.size(); i++) {
                AssembledNode n = nodes.get(i);
                StudioSubBodyAdapter adapter = new StudioSubBodyAdapter(n, registry, ctx, frame);
                if (i == 0) {
                    workflow.setStartComp(n.id(), adapter, null, null);
                } else if (i == nodes.size() - 1) {
                    workflow.setEndComp(n.id(), adapter, null, null);
                } else {
                    workflow.addWorkflowComp(n.id(), adapter, null, null);
                }
            }
            for (int i = 0; i < ids.size() - 1; i++) {
                workflow.addConnection(ids.get(i), ids.get(i + 1));
            }
        }
        return new AssembledSub(workflow, new SubWorkflowComponent(workflow), ids, frame);
    }

    record AssembledSub(
            Workflow workflow,
            SubWorkflowComponent component,
            List<String> nodeIds,
            AtomicReference<Map<String, Object>> frame) {}

    /**
     * Adapts Studio IR node into a core {@link WorkflowComponent}; shares mutable frame so linear
     * userFields merge works without per-edge {@code inputs_schema}.
     */
    static final class StudioSubBodyAdapter extends WorkflowComponent {
        private final AssembledNode child;
        private final NodeTypeRegistry registry;
        private final NodeBuildContext ctx;
        private final AtomicReference<Map<String, Object>> frame;

        StudioSubBodyAdapter(
                AssembledNode child,
                NodeTypeRegistry registry,
                NodeBuildContext ctx,
                AtomicReference<Map<String, Object>> frame) {
            this.child = child;
            this.registry = registry;
            this.ctx = ctx;
            this.frame = frame;
        }

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> base = frame.get();
            if (base == null) {
                base = new LinkedHashMap<>();
            }
            Map<String, Object> merged = new LinkedHashMap<>(base);
            if (inputs instanceof Map<?, ?> in) {
                in.forEach((k, v) -> {
                    if (v != null) {
                        merged.put(String.valueOf(k), v);
                    }
                });
            }
            try {
                ComponentExecutable exec = registry.create(child, ctx);
                Object out = exec.invoke(Map.of("userFields", DeepCopies.map(merged)), session, context);
                Map<String, Object> uf = extractUserFields(out);
                Map<String, Object> next = new LinkedHashMap<>(merged);
                next.putAll(uf);
                // setVariable returns {} (Python None); side effects live on WorkflowVariableScope
                if (ctx != null && ctx.variableScope() != null) {
                    next.putAll(ctx.variableScope().snapshot());
                }
                frame.set(next);
                return new LinkedHashMap<>(next);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final class StudioSubPassthroughAdapter extends WorkflowComponent {
        private final AtomicReference<Map<String, Object>> frame;

        StudioSubPassthroughAdapter(AtomicReference<Map<String, Object>> frame) {
            this.frame = frame;
        }

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> base = frame.get() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(frame.get());
            if (inputs instanceof Map<?, ?> in) {
                in.forEach((k, v) -> base.put(String.valueOf(k), v));
            }
            frame.set(base);
            return new LinkedHashMap<>(base);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractUserFields(Object out) {
        if (!(out instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Object uf = m.get("userFields");
        if (uf instanceof Map<?, ?> um) {
            Map<String, Object> copy = new LinkedHashMap<>();
            um.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }
}
