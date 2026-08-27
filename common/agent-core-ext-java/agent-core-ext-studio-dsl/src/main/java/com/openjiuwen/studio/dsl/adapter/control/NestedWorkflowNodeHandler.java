/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.components.flow.SubWorkflowComponent;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.flowsubworkflow.FlowSubWorkflowConfig;
import com.openjiuwen.studio.dsl.flowsubworkflow.FlowSubWorkflowEngine;
import com.openjiuwen.studio.dsl.flowsubworkflow.SubWorkflowException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.DeepCopies;
import com.openjiuwen.studio.dsl.util.SanitizeMessage;
import com.openjiuwen.studio.dsl.util.SessionStateIsolator;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * jiuwen.subWorkflow — strict 1:1 with Python {@code sub_workflow.py} ({@code SubWorkflow}).
 *
 * <p>Python semantics live in {@link FlowSubWorkflowEngine}. This Handler assembles the child IR and
 * drives Pregel ({@link SubWorkflowComponent}) or linear Studio short-circuit; soft hang is the host
 * stand-in when {@code session.interact} / GraphInterrupt is not wired (Python raises interact).
 *
 * @since 2026-08-17
 */

public final class NestedWorkflowNodeHandler implements NodeHandlerFactory {
    private final NodeTypeRegistry registry;

    /**
     * NestedWorkflowNodeHandler.
     *
     * @param registry registry
     * @since 0.1.0
     */

    public NestedWorkflowNodeHandler(NodeTypeRegistry registry) {
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
        return "jiuwen.subWorkflow";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.workflowComposite");
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
        int next = ctx.nestingDepth() + 1;
        if (next > ctx.maxNestingDepth()) {
            throw new NodeExecutionException(
                    node.id(),
                    "jiuwen.subWorkflow",
                    NodeCauseCode.NESTING_DEPTH_EXCEEDED,
                    "depth=" + next + ", max=" + ctx.maxNestingDepth());
        }
        AssembledWorkflow child;
        try {
            child = ctx.subWorkflowResolver().resolve(node.configs());
        } catch (NodeExecutionException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.subWorkflow", NodeCauseCode.SUBWORKFLOW_REF_INVALID, e.getMessage(), e);
        }
        if (child == null) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.subWorkflow", NodeCauseCode.SUBWORKFLOW_REF_INVALID, "resolver returned null");
        }
        NodeBuildContext childCtx = ctx.childDepth();
        Map<String, ComponentExecutable> childExec =
                new WorkflowAssemblyBridge(registry).mapExecutables(child, childCtx);
        AtomicReference<Map<String, Object>> frame = new AtomicReference<>(new LinkedHashMap<>());
        StudioSubWorkflowAssembler.AssembledSub assembled =
                StudioSubWorkflowAssembler.assemble(child, node.id(), registry, childCtx, frame);
        FlowSubWorkflowConfig config = FlowSubWorkflowConfig.fromNodeConfigs(node.id(), node.configs());
        FlowSubWorkflowEngine engine = new FlowSubWorkflowEngine(config);
        return new NestedExecutable(node, childExec, next, assembled, childCtx, engine);
    }

    public static final class NestedExecutable extends AbstractStudioNode {
        private final Map<String, ComponentExecutable> childExec;
        private final int depth;
        private final StudioSubWorkflowAssembler.AssembledSub assembled;
        private final NodeBuildContext childCtx;
        private final FlowSubWorkflowEngine engine;

        NestedExecutable(
                AssembledNode node,
                Map<String, ComponentExecutable> childExec,
                int depth,
                StudioSubWorkflowAssembler.AssembledSub assembled,
                NodeBuildContext childCtx,
                FlowSubWorkflowEngine engine) {
            super(node);
            this.childExec = childExec;
            this.depth = depth;
            this.assembled = assembled;
            this.childCtx = childCtx;
            this.engine = engine;
        }

        /**
         * Exposed for tests / hosts that need the core SubWorkflowComponent.
         *
         * @return result
         * @since 0.1.0
         */

        public SubWorkflowComponent coreComponent() {
            return assembled.component();
        }

        /**
         * Python {@code SubWorkflow} engine.
         *
         * @return result
         * @since 0.1.0
         */

        public FlowSubWorkflowEngine engine() {
            return engine;
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
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return SessionStateIsolator.runIsolated(
                    session, () -> invokeChild(inputs, session, context));
        }

        /**
         * stream.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            return SessionStateIsolator.runIsolated(
                    session, () -> streamChild(asMap(inputs), session, context, inputs));
        }

        private NodePayload invokeChild(
                Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Object prepared =
                    engine.prepareChildInputs(inputs, session, context, inputs, depth, false);
            // Studio resume helpers (InteractiveInput raw_inputs) when Engine returned a map envelope
            Object payload = prepared instanceof InteractiveInput
                    ? prepared
                    : SubInteractiveSupport.prepareChildPayload(
                            prepared instanceof Map<?, ?> pm ? castPrepared(pm) : inputs, session, inputs);
            if (payload instanceof InteractiveInput) {
                engine.nodeState()
                        .setStatus(
                                com.openjiuwen.studio.dsl.flowsubworkflow.SubWorkflowExecutionStatus
                                        .USER_INTERACT);
            }
            Map<String, Object> parentSnap = engine.enterRequestScope(session, prepared instanceof Map<?, ?>
                    ? castPrepared((Map<?, ?>) prepared)
                    : inputs);
            try {
                Map<String, Object> dictInputs =
                        prepared instanceof Map<?, ?> pm ? castPrepared(pm) : DeepCopies.map(inputs);
                if (canUseCoreSubWorkflow(session) || forceCore(node.configs())) {
                    return invokeViaCore(payload, dictInputs, session, context);
                }
                return invokeLinear(SubInteractiveSupport.unwrapForLinear(payload, dictInputs), session, context);
            } finally {
                engine.exitRequestScope(session, parentSnap);
            }
        }

        private static Map<String, Object> castPrepared(Map<?, ?> pm) {
            Map<String, Object> out = new LinkedHashMap<>();
            pm.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }

        private NodePayload invokeViaCore(
                Object payload, Map<String, Object> prepared, NodeSessionApi session, ModelContext context) {
            Map<String, Object> seed = userFieldsOf(prepared);
            assembled.frame().set(new LinkedHashMap<>(seed));
            NodeSessionApi coreSession = ensureSession(session, node.id());
            Map<String, Object> wrapped = new LinkedHashMap<>();
            Object childInputs = payload instanceof Map<?, ?> pm && pm.get("__studio_child_inputs__") != null
                    ? pm.get("__studio_child_inputs__")
                    : (payload instanceof InteractiveInput ? payload : userFieldsOf(prepared));
            wrapped.put(Constant.INPUTS_KEY, childInputs);
            wrapped.put(Constant.CONFIG_KEY, Map.of());
            try {
                Object raw = assembled.component().invoke(wrapped, coreSession, context);
                Map<String, Object> done = new LinkedHashMap<>(assembled.frame().get() == null
                        ? Map.of()
                        : assembled.frame().get());
                mergeCoreResult(done, raw);
                if (raw instanceof Map<?, ?> rm) {
                    Map<String, Object> rawMap = new LinkedHashMap<>();
                    rm.forEach((k, v) -> rawMap.put(String.valueOf(k), v));
                    if (rawMap.containsKey("error_code") && rawMap.containsKey("error_message")) {
                        throw SubWorkflowException.executionError(
                                String.valueOf(rawMap.getOrDefault("error_message", "Unknown error")));
                    }
                    OptionalInteraction interaction = findInteraction(rawMap);
                    if (interaction.present) {
                        return interruptPayload(done);
                    }
                    FlowSubWorkflowEngine.ParsedChildResult parsed = engine.parseNormalChildInvokeResult(rawMap);
                    if (parsed.responseContent() != null && !parsed.responseContent().isEmpty()) {
                        done.putIfAbsent("answer", parsed.responseContent());
                        done.putIfAbsent("result", parsed.responseContent());
                    }
                    if (parsed.userFields() != null && !parsed.userFields().isEmpty()) {
                        done.putAll(parsed.userFields());
                    }
                }
                if (engine.stillInterrupted(done, coreSession) || engine.stillInterrupted(done, session)) {
                    return interruptPayload(done);
                }
                return successPayload(done, session);
            } catch (SubWorkflowException e) {
                engine.setLastChildCompleted(false);
                throw wrapChildFailure(e, "child failed: ");
            } catch (RuntimeException e) {
                if (isGraphInterrupt(e)) {
                    Map<String, Object> hang = new LinkedHashMap<>(
                            assembled.frame().get() == null ? Map.of() : assembled.frame().get());
                    engine.markGraphInterrupt(
                            String.valueOf(hang.getOrDefault("answer", "")), hang);
                    engine.traceInterruptMarker(
                            session, String.valueOf(hang.getOrDefault("answer", "")), hang);
                    return interruptPayload(hang);
                }
                engine.setLastChildCompleted(false);
                throw wrapChildFailure(e, "child failed: ");
            }
        }

        private NodePayload invokeLinear(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> current = DeepCopies.map(inputs);
            try {
                for (ComponentExecutable exec : childExec.values()) {
                    Object out = exec.invoke(current, session, context);
                    Map<String, Object> produced = new LinkedHashMap<>();
                    if (out instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> produced.put(String.valueOf(k), v));
                    }
                    current =
                            DeepCopies.map(
                                    WorkflowAssemblyBridge.mergeLinearStep(
                                            current,
                                            produced,
                                            childCtx == null ? null : childCtx.variableScope()));
                    Map<String, Object> childUf = userFieldsOf(current);
                    if (engine.stillInterrupted(childUf, session)) {
                        return interruptPayload(childUf);
                    }
                }
                return successPayload(userFieldsOf(current), session);
            } catch (NodeExecutionException e) {
                engine.setLastChildCompleted(false);
                throw wrapChildFailure(e, "child failed: ");
            } catch (RuntimeException e) {
                if (isGraphInterrupt(e)) {
                    engine.markGraphInterrupt("", Map.of());
                    engine.traceInterruptMarker(session, "", Map.of());
                    return interruptPayload(new LinkedHashMap<>());
                }
                engine.setLastChildCompleted(false);
                throw wrapChildFailure(e, "child failed: ");
            }
        }

        private NodePayload successPayload(Map<String, Object> childUf, NodeSessionApi session) {
            Map<String, Object> python = engine.packageStudioSuccess(childUf, depth, session);
            return NodePayload.ofFields(python);
        }

        private static final class OptionalInteraction {
            final boolean present;

            OptionalInteraction(boolean present) {
                this.present = present;
            }
        }

        private OptionalInteraction findInteraction(Map<String, Object> rawMap) {
            return new OptionalInteraction(engine.findInteractionChunkInChildResult(rawMap).isPresent());
        }
        private Iterator<Object> streamChild(
                Map<String, Object> inputs,
                NodeSessionApi session,
                ModelContext context,
                Object originalInputs) {
            Object prepared =
                    engine.prepareChildInputs(inputs, session, context, originalInputs, depth, false);
            Object payload = prepared instanceof InteractiveInput
                    ? prepared
                    : SubInteractiveSupport.prepareChildPayload(
                            prepared instanceof Map<?, ?> pm ? castPrepared(pm) : inputs,
                            session,
                            originalInputs);
            Map<String, Object> dictInputs =
                    prepared instanceof Map<?, ?> pm ? castPrepared(pm) : DeepCopies.map(inputs);
            Map<String, Object> parentSnap = engine.enterRequestScope(session, dictInputs);
            try {
                if (canUseCoreSubWorkflow(session) || forceCore(node.configs())) {
                    return streamViaCore(payload, dictInputs, session, context);
                }
                return streamLinear(SubInteractiveSupport.unwrapForLinear(payload, dictInputs), session, context);
            } finally {
                engine.exitRequestScope(session, parentSnap);
            }
        }

        private Iterator<Object> streamViaCore(
                Object payload, Map<String, Object> prepared, NodeSessionApi session, ModelContext context) {
            List<Object> frames = new ArrayList<>();
            Map<String, Object> seed = userFieldsOf(prepared);
            assembled.frame().set(new LinkedHashMap<>(seed));
            NodeSessionApi coreSession = ensureSession(session, node.id());
            Map<String, Object> wrapped = new LinkedHashMap<>();
            Object childInputs = payload instanceof Map<?, ?> pm && pm.get("__studio_child_inputs__") != null
                    ? pm.get("__studio_child_inputs__")
                    : (payload instanceof InteractiveInput ? payload : userFieldsOf(prepared));
            wrapped.put(Constant.INPUTS_KEY, childInputs);
            wrapped.put(Constant.CONFIG_KEY, Map.of());
            int index = 0;
            try {
                Iterator<Object> it = assembled.component().stream(wrapped, coreSession, context);
                Object last = null;
                while (it != null && it.hasNext()) {
                    last = it.next();
                    Object tagged = tagSubFrame(toStudioFrame(last, index), index++);
                    FlowSubWorkflowEngine.StreamChunkAction action = engine.processStreamChunk(tagged);
                    // Python skips start/workflow_* in parent yield; Studio still keeps frames for hosts
                    if (action.kind() == FlowSubWorkflowEngine.StreamChunkAction.Kind.SKIP
                            && tagged instanceof Map<?, ?> tm) {
                        String t = String.valueOf(tm.get("type"));
                        if ("start".equals(t) || "workflow_start".equals(t)) {
                            continue;
                        }
                    }
                    frames.add(tagged);
                    writeFrame(session, tagged);
                }
                Map<String, Object> done = new LinkedHashMap<>(
                        assembled.frame().get() == null ? Map.of() : assembled.frame().get());
                if (engine.stillInterrupted(done, coreSession) || engine.stillInterrupted(done, session)) {
                    Map<String, Object> interactData = new LinkedHashMap<>();
                    interactData.put("should_interrupt", true);
                    interactData.put("nestedWorkflowState", "user_interact");
                    interactData.put("user_fields", done);
                    Map<String, Object> interact = new LinkedHashMap<>();
                    interact.put("type", "user_interact");
                    interact.put("index", index);
                    interact.put("data", interactData);
                    frames.add(interact);
                    writeFrame(session, interact);
                } else if (frames.isEmpty() || !hasWorkflowEnd(frames)) {
                    Map<String, Object> python = engine.packageStudioSuccess(done, depth, session);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> uf = python.get(FlowSubWorkflowEngine.USER_FIELDS) instanceof Map<?, ?>
                            ? (Map<String, Object>) python.get(FlowSubWorkflowEngine.USER_FIELDS)
                            : done;
                    frames.add(workflowEndFrame(uf, index));
                    writeFrame(session, frames.get(frames.size() - 1));
                }
            } catch (RuntimeException e) {
                if (isGraphInterrupt(e)) {
                    Map<String, Object> hang = new LinkedHashMap<>(
                            assembled.frame().get() == null ? Map.of() : assembled.frame().get());
                    Map<String, Object> interactData = new LinkedHashMap<>();
                    interactData.put("should_interrupt", true);
                    interactData.put("nestedWorkflowState", "user_interact");
                    interactData.put("user_fields", hang);
                    Map<String, Object> interact = new LinkedHashMap<>();
                    interact.put("type", "user_interact");
                    interact.put("index", index);
                    interact.put("data", interactData);
                    frames.add(interact);
                    return frames.iterator();
                }
                String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.subWorkflow",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "child stream failed: " + SanitizeMessage.sanitize(raw),
                        e);
            }
            return frames.iterator();
        }

        private Iterator<Object> streamLinear(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            List<Object> frames = new ArrayList<>();
            Map<String, Object> current = DeepCopies.map(inputs);
            boolean interrupted = false;
            try {
                int index = 0;
                for (ComponentExecutable exec : childExec.values()) {
                    Iterator<Object> childStream = tryStream(exec, current, session, context);
                    if (childStream != null) {
                        Object last = null;
                        while (childStream.hasNext()) {
                            last = childStream.next();
                            frames.add(tagSubFrame(last, index++));
                            writeFrame(session, last);
                        }
                        current = mergeCurrentFromStream(current, last);
                    } else {
                        Object out = exec.invoke(current, session, context);
                        if (out instanceof Map<?, ?>) {
                            current = DeepCopies.map(AbstractStudioNode.asMap(out));
                        }
                        Map<String, Object> childUf = userFieldsOf(current);
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put(
                                "answer",
                                String.valueOf(childUf.getOrDefault("answer", childUf.getOrDefault("result", ""))));
                        data.put("user_fields", childUf);
                        data.put("node_id", node.id());
                        data.put("node_type", "jiuwen.subWorkflow");
                        Map<String, Object> partial = new LinkedHashMap<>();
                        partial.put("type", StudioStreamFrames.PARTIAL_CONTENT);
                        partial.put("index", index++);
                        partial.put("data", data);
                        frames.add(partial);
                        writeFrame(session, partial);
                    }

                    Map<String, Object> childUf = userFieldsOf(current);
                    if (engine.stillInterrupted(childUf, session)) {
                        interrupted = true;
                        Map<String, Object> hang = interruptPayload(childUf).toInvokeMap();
                        Map<String, Object> hangUf = hang.get("userFields") instanceof Map<?, ?>
                                ? AbstractStudioNode.asMap(hang.get("userFields"))
                                : childUf;
                        Map<String, Object> interactData = new LinkedHashMap<>();
                        interactData.put("should_interrupt", true);
                        interactData.put("nestedWorkflowState", "user_interact");
                        interactData.put("user_fields", hangUf);
                        Map<String, Object> interact = new LinkedHashMap<>();
                        interact.put("type", "user_interact");
                        interact.put("index", index++);
                        interact.put("data", interactData);
                        frames.add(interact);
                        writeFrame(session, interact);
                        break;
                    }
                }
                if (!interrupted) {
                    Map<String, Object> python =
                            engine.packageStudioSuccess(userFieldsOf(current), depth, session);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> done = python.get(FlowSubWorkflowEngine.USER_FIELDS) instanceof Map<?, ?>
                            ? (Map<String, Object>) python.get(FlowSubWorkflowEngine.USER_FIELDS)
                            : userFieldsOf(current);
                    frames.add(workflowEndFrame(done, index));
                    writeFrame(session, frames.get(frames.size() - 1));
                }
            } catch (NodeExecutionException e) {
                String raw = e.getMessage() == null ? "" : e.getMessage();
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.subWorkflow",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "child stream failed: " + SanitizeMessage.sanitize(raw),
                        e);
            } catch (RuntimeException e) {
                if (isGraphInterrupt(e)) {
                    Map<String, Object> hang = interruptPayload(new LinkedHashMap<>()).toInvokeMap();
                    Map<String, Object> hangUf = hang.get("userFields") instanceof Map<?, ?>
                            ? AbstractStudioNode.asMap(hang.get("userFields"))
                            : Map.of();
                    Map<String, Object> interactData = new LinkedHashMap<>();
                    interactData.put("should_interrupt", true);
                    interactData.put("nestedWorkflowState", "user_interact");
                    interactData.put("user_fields", hangUf);
                    Map<String, Object> interact = new LinkedHashMap<>();
                    interact.put("type", "user_interact");
                    interact.put("index", frames.size());
                    interact.put("data", interactData);
                    frames.add(interact);
                    writeFrame(session, interact);
                    return frames.iterator();
                }
                String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.subWorkflow",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "child stream failed: " + SanitizeMessage.sanitize(raw),
                        e);
            }
            return frames.iterator();
        }

        private NodeExecutionException wrapChildFailure(RuntimeException e, String prefix) {
            String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new NodeExecutionException(
                    node.id(),
                    "jiuwen.subWorkflow",
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    prefix + SanitizeMessage.sanitize(raw),
                    e);
        }

        private Map<String, Object> workflowEndFrame(Map<String, Object> done, int index) {
            Map<String, Object> endData = new LinkedHashMap<>();
            endData.put(
                    "answer",
                    String.valueOf(done.getOrDefault("answer", done.getOrDefault("result", ""))));
            endData.put("user_fields", done);
            endData.put("node_id", node.id());
            endData.put("node_type", "jiuwen.subWorkflow");
            endData.put("is_final", true);
            Map<String, Object> endFrame = new LinkedHashMap<>();
            endFrame.put("type", StudioStreamFrames.WORKFLOW_END);
            endFrame.put("index", index);
            endFrame.put("data", endData);
            return endFrame;
        }

        private static boolean hasWorkflowEnd(List<Object> frames) {
            for (Object f : frames) {
                if (f instanceof Map<?, ?> m && StudioStreamFrames.WORKFLOW_END.equals(String.valueOf(m.get("type")))) {
            return true;
        }
            }
            return false;
        }

        private static Object toStudioFrame(Object chunk, int index) {
            if (chunk instanceof Map<?, ?>) {
            return chunk;
        }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", StudioStreamFrames.PARTIAL_CONTENT);
            frame.put("index", index);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", chunk == null ? "" : String.valueOf(chunk));
            frame.put("data", data);
            return frame;
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
            Object uf = m.get("userFields");
            if (uf instanceof Map<?, ?> um) {
                um.forEach((k, v) -> done.put(String.valueOf(k), v));
            }
        }

        private static boolean canUseCoreSubWorkflow(NodeSessionApi session) {
            if (session == null) {
            return false;
        }
            try {
                return session.getInner() != null;
            } catch (RuntimeException | Error e) {
                return false;
            }
        }

        private static boolean forceCore(Map<String, Object> configs) {
            if (configs == null) {
            return false;
        }
            Object v = configs.getOrDefault("useCoreSubWorkflow", configs.get("use_core_sub_workflow"));
            return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
        }

        private static NodeSessionApi ensureSession(NodeSessionApi session, String nodeId) {
            if (session != null) {
                try {
                    if (session.getInner() != null) {
            return session;
        }
                } catch (RuntimeException | Error ignored) {
                    // mock
                }
            }
            WorkflowSession wf =
                    new WorkflowSession("studio-sub-wf", null, null, InMemoryState.create(), null);
            return new NodeSessionApi(new NodeSession(wf, nodeId == null ? "sub" : nodeId));
        }

        private static boolean isGraphInterrupt(Throwable e) {
            Throwable cur = e;
            while (cur != null) {
                if (cur instanceof GraphInterrupt
                        || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                    return true;
                }
                cur = cur.getCause();
            }
            return false;
        }

        private static Iterator<Object> tryStream(
                ComponentExecutable exec, Object inputs, NodeSessionApi session, ModelContext context) {
            try {
                Iterator<Object> it = exec.stream(inputs, session, context);
                if (it == null || !it.hasNext()) {
                    return Collections.emptyIterator();
                }
                List<Object> buf = new ArrayList<>();
                buf.add(it.next());
                while (it.hasNext()) {
                    buf.add(it.next());
                }
                return buf.iterator();
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("missing required method: stream") || msg.contains("stream()")) {
                    return Collections.emptyIterator();
                }
                if (e instanceof NodeExecutionException) {
                    throw e;
                }
                return Collections.emptyIterator();
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> mergeCurrentFromStream(Map<String, Object> current, Object lastFrame) {
            if (!(lastFrame instanceof Map<?, ?> fm)) {
                return current;
            }
            Object data = fm.get("data");
            Map<String, Object> next = DeepCopies.map(current);
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(next));
            if (data instanceof Map<?, ?> dm) {
                Object nestedUf = dm.get("user_fields");
                if (nestedUf instanceof Map<?, ?> num) {
                    num.forEach((k, v) -> uf.put(String.valueOf(k), v));
                } else {
                    Object answer = dm.get("answer");
                    if (answer != null) {
                        uf.put("answer", answer);
                        uf.putIfAbsent("result", answer);
                        uf.putIfAbsent("response", answer);
                    }
                    dm.forEach((k, v) -> {
                        String key = String.valueOf(k);
                        if (!"type".equals(key) && !"index".equals(key)) {
                            uf.putIfAbsent(key, v);
                        }
                    });
                }
            }
            next.put("userFields", uf);
            return next;
        }

        private static Object tagSubFrame(Object frame, int index) {
            if (!(frame instanceof Map<?, ?> m)) {
            return frame;
        }
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            // Message/Engine frames use payload; Studio hosts / tests often read data
            if (!copy.containsKey("data") && copy.containsKey("payload")) {
                copy.put("data", copy.get("payload"));
            }
            copy.putIfAbsent("index", index);
            copy.put("is_sub", true);
            return copy;
        }

        private static void writeFrame(NodeSessionApi session, Object frame) {
            if (session == null || !(frame instanceof Map<?, ?>)) {
            return;
        }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> fm = (Map<String, Object>) frame;
                session.writeCustomStream(fm);
            } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
                // mock
            }
        }

        private NodePayload interruptPayload(Map<String, Object> childUf) {
            return NodePayload.userFields(engine.packageSoftHang(childUf, depth));
        }
        static boolean isChildInterrupt(Map<String, Object> uf) {
            return NestedWorkflowNodeHandler.isChildInterrupt(uf);
        }
        static boolean detectInterruptInSession(NodeSessionApi session) {
            return NestedWorkflowNodeHandler.detectInterruptInSession(session);
        }
    }

    /**
     * isChildInterrupt.
     *
     * @param uf uf
     * @return result
     * @since 0.1.0
     */

    public static boolean isChildInterrupt(Map<String, Object> uf) {
        return new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("", Map.of()))
                .isChildInterruptFields(uf);
    }

    /**
     * detectInterruptInSession.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public static boolean detectInterruptInSession(NodeSessionApi session) {
        return new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("", Map.of()))
                .detectInterruptInSession(session);
    }
}
