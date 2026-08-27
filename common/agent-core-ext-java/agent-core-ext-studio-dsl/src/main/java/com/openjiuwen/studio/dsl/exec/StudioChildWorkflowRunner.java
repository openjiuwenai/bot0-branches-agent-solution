/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.studio.dsl.adapter.control.StudioSubWorkflowAssembler;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run an assembled child workflow through core {@link com.openjiuwen.core.workflow.Workflow}
 * (Python {@code workflow.stream(...)} / {@code SubWorkflowComponent}), not declaration-order
 * {@code ComponentExecutable.invoke}.
 *
 * @since 2026-08-27
 */

public final class StudioChildWorkflowRunner {
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";

    private StudioChildWorkflowRunner() {}

    /**
     * Invoke child workflow and return merged userFields envelope.
     *
     * @param child child IR
     * @param nestNodeId parent node id for session naming
     * @param registry node registry
     * @param childCtx child build context (closed on exit)
     * @param subInputs child inputs
     * @param session session
     * @param context model context
     * @return merged output map (includes {@link #USER_FIELDS} when present)
     */

    public static Map<String, Object> invoke(
            AssembledWorkflow child,
            String nestNodeId,
            NodeTypeRegistry registry,
            NodeBuildContext childCtx,
            Map<String, Object> subInputs,
            NodeSessionApi session,
            ModelContext context) {
        AtomicReference<Map<String, Object>> frame = new AtomicReference<>(new LinkedHashMap<>());
        Map<String, Object> prepared = normalizeInputs(subInputs);
        frame.set(new LinkedHashMap<>(userFieldsOf(prepared)));
        try {
            StudioSubWorkflowAssembler.AssembledSub assembled =
                    StudioSubWorkflowAssembler.assemble(child, nestNodeId, registry, childCtx, frame);
            NodeSessionApi coreSession = ensureSession(session, nestNodeId);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(Constant.INPUTS_KEY, userFieldsOf(prepared));
            wrapped.put(Constant.CONFIG_KEY, Map.of());
            Object raw = assembled.component().invoke(wrapped, coreSession, context);
            Map<String, Object> done =
                    new LinkedHashMap<>(frame.get() == null ? Map.of() : frame.get());
            mergeCoreResult(done, raw);
            if (!done.containsKey(USER_FIELDS)) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put(USER_FIELDS, done);
                return envelope;
            }
            return done;
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            throw e;
        } finally {
            if (childCtx != null) {
                childCtx.variableScope().close();
            }
        }
    }

    private static Map<String, Object> normalizeInputs(Map<String, Object> subInputs) {
        Map<String, Object> prepared = DeepCopies.map(subInputs == null ? Map.of() : subInputs);
        if (!prepared.containsKey(USER_FIELDS)) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put(USER_FIELDS, new LinkedHashMap<>(prepared));
            return wrap;
        }
        return prepared;
    }

    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    private static void mergeCoreResult(Map<String, Object> done, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
        return;
    }
        m.forEach((k, v) -> {
            if (v != null) {
                done.putIfAbsent(String.valueOf(k), v);
            }
        });
        Object uf = m.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> um) {
            um.forEach((k, v) -> done.put(String.valueOf(k), v));
        }
    }

    private static NodeSessionApi ensureSession(NodeSessionApi session, String nodeId) {
        if (session != null) {
            try {
                if (session.getInner() != null) {
        return session;
    }
            } catch (RuntimeException | Error ignored) {
                // mock session without inner
            }
        }
        WorkflowSession wf =
                new WorkflowSession("studio-child-wf", null, null, InMemoryState.create(), null);
        return new NodeSessionApi(new NodeSession(wf, nodeId == null ? "child" : nodeId));
    }

    private static void rethrowGraphInterrupt(RuntimeException e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof GraphInterrupt
                    || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                throw e;
            }
            cur = cur.getCause();
        }
    }
}
