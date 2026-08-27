/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.complexintent;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.contract.SubWorkflowResolver;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.StudioChildWorkflowRunner;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionLlmDetector;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ComplexIntentDetection engine — strict 1:1 with Python {@code complex_intent_detection.py}.
 *
 * <p>Composes {@link IntentDetectionEngine}; branch SubWorkflow via {@link SubWorkflowResolver}
 * + linear assembly. Tests inject {@link TestBridge} via constructor or
 * {@link com.openjiuwen.studio.dsl.exec.StudioEngineTestOverrides}.
 *
 * @since 2026-08-26
 */

public final class ComplexIntentDetectionEngine {
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";
    /**
     * KEY_CLASSIFICATION_ID.
     * @since 0.1.0
     */
    public static final String KEY_CLASSIFICATION_ID = "classificationId";
    /**
     * RESPONSE_CONTENT.
     * @since 0.1.0
     */
    public static final String RESPONSE_CONTENT = "responseContent";
    /**
     * INPUT.
     * @since 0.1.0
     */
    public static final String INPUT = "input";

    /**
     * Test stub for intent + optional sub-workflow (mirrors patching IntentDetection / SubWorkflow).
     */
    public interface TestBridge {
        Map<String, Object> intentResult(Map<String, Object> convertedInputs);

        /**
         * {@code null} = skip / no workflow result.
         *
         * @param workflowId workflowId
         * @param subInputs subInputs
         * @return result
         * @since 0.1.0
         */
        Map<String, Object> subWorkflowResult(String workflowId, Map<String, Object> subInputs);
    }

    private final String nodeId;
    private final ComplexIntentDetectionConfig config;
    private final Map<String, Map<String, Object>> branchesById;
    private final Map<String, Map<String, Object>> wfToBranches;
    private final Map<String, Map<String, List<List<String>>>> branch2group;
    private final IntentDetectionEngine intentDetection;
    private final SubWorkflowResolver subWorkflowResolver;
    private final NodeTypeRegistry nodeTypeRegistry;
    private final NodeBuildContext buildContext;
    private final TestBridge presetBridge;

    private ComplexIntentState state = new ComplexIntentState();

    /**
     * ComplexIntentDetectionEngine.
     * @param nodeId nodeId
     * @param nodeConfigs nodeConfigs
     * @since 0.1.0
     */
    public ComplexIntentDetectionEngine(String nodeId, Map<String, Object> nodeConfigs) {
        this(nodeId, nodeConfigs, null, null, null, null, null);
    }
    /**
     * ComplexIntentDetectionEngine.
     * @param nodeId nodeId
     * @param nodeConfigs nodeConfigs
     * @param subWorkflowResolver subWorkflowResolver
     * @param nodeTypeRegistry nodeTypeRegistry
     * @param buildContext buildContext
     * @since 0.1.0
     */
    public ComplexIntentDetectionEngine(
            String nodeId,
            Map<String, Object> nodeConfigs,
            SubWorkflowResolver subWorkflowResolver,
            NodeTypeRegistry nodeTypeRegistry,
            NodeBuildContext buildContext) {
        this(nodeId, nodeConfigs, subWorkflowResolver, nodeTypeRegistry, buildContext, null, null);
    }
    /**
     * ComplexIntentDetectionEngine.
     * @param nodeId nodeId
     * @param nodeConfigs nodeConfigs
     * @param subWorkflowResolver subWorkflowResolver
     * @param nodeTypeRegistry nodeTypeRegistry
     * @param buildContext buildContext
     * @param toolRegistry toolRegistry
     * @since 0.1.0
     */
    public ComplexIntentDetectionEngine(
            String nodeId,
            Map<String, Object> nodeConfigs,
            SubWorkflowResolver subWorkflowResolver,
            NodeTypeRegistry nodeTypeRegistry,
            NodeBuildContext buildContext,
            ToolRegistry toolRegistry) {
        this(nodeId, nodeConfigs, subWorkflowResolver, nodeTypeRegistry, buildContext, toolRegistry, null);
    }
    /**
     * ComplexIntentDetectionEngine.
     * @param nodeId nodeId
     * @param nodeConfigs nodeConfigs
     * @param subWorkflowResolver subWorkflowResolver
     * @param nodeTypeRegistry nodeTypeRegistry
     * @param buildContext buildContext
     * @param toolRegistry toolRegistry
     * @param testBridge testBridge
     * @since 0.1.0
     */
    public ComplexIntentDetectionEngine(
            String nodeId,
            Map<String, Object> nodeConfigs,
            SubWorkflowResolver subWorkflowResolver,
            NodeTypeRegistry nodeTypeRegistry,
            NodeBuildContext buildContext,
            ToolRegistry toolRegistry,
            TestBridge testBridge) {
        this.presetBridge = testBridge;
        this.nodeId = nodeId == null ? "cid" : nodeId;
        this.config = ComplexIntentDetectionConfig.from(this.nodeId, nodeConfigs);
        this.branchesById = new LinkedHashMap<>();
        this.wfToBranches = new LinkedHashMap<>();
        for (Map<String, Object> b : config.branches()) {
            String id = str(b.get("id"));
            branchesById.put(id, b);
            Object configs = b.get("configs");
            if (configs instanceof Map<?, ?> cm) {
                Object wf = cm.get("workflow_id");
                if (wf != null) {
                    wfToBranches.put(String.valueOf(wf), b);
                }
            }
        }
        this.branch2group = buildBranchToGroup(config.groups());
        IntentDetectionLlmDetector.ModelInvoker intentInvoker = null;
        if (buildContext != null && buildContext.testOverrides() != null) {
            intentInvoker = buildContext.testOverrides().intentInvoker();
        }
        if (intentInvoker != null) {
            this.intentDetection =
                    new IntentDetectionEngine(
                            this.nodeId + "-intent", config.toIntentDetectionConfigs(), intentInvoker, toolRegistry);
        } else {
            this.intentDetection =
                    /**
                     * IntentDetectionEngine.
                     * @return result
                     * @since 0.1.0
                     */
                    new IntentDetectionEngine(this.nodeId + "-intent", config.toIntentDetectionConfigs(), toolRegistry);
        }
        this.subWorkflowResolver = subWorkflowResolver;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.buildContext = buildContext;
    }

    /**
     * getState.
     *
     * @return result
     * @since 0.1.0
     */

    public ComplexIntentState getState() {
        return state;
    }

    /**
     * loadState.
     *
     * @param s s
     * @since 0.1.0
     */

    public void loadState(ComplexIntentState s) {
        this.state = s == null ? new ComplexIntentState() : s.copy();
    }

    /**
     * reset.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean reset() {
        this.state = new ComplexIntentState();
        return true;
    }

    /**
     * Python {@code invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        try {
            loadStateFromSession(session);
            Map<String, Object> converted = convertInput(inputs);

            Map.Entry<Map<String, Object>, String> intentPair = getIntentResult(converted, session, context);
            Map<String, Object> intentResult = intentPair.getKey();
            String intentBranch = intentPair.getValue();

            if (Boolean.TRUE.equals(converted.get("__single_debug_recovery__"))) {
                /**
                 * wrapUserFields.
                 * @param intentResult intentResult
                 * @return result
                 * @since 0.1.0
                 */
                return wrapUserFields(intentResult);
            }

            Map.Entry<String, Map<String, Object>> wfPair =
                    executeBranchWorkflow(converted, intentBranch, session, context);
            String wfId = wfPair == null ? null : wfPair.getKey();
            Map<String, Object> wfResult = wfPair == null ? null : wfPair.getValue();

            Map<String, Object> enrichedIntent = new LinkedHashMap<>(intentResult);
            enrichedIntent.put(
                    RESPONSE_CONTENT,
                    wfResult == null ? "" : str(wfResult.getOrDefault(RESPONSE_CONTENT, "")));

            Map<String, Object> result = getAggregateResult(wfId, wfResult, enrichedIntent);
            storeStateToSession(session);
            /**
             * wrapUserFields.
             * @param result result
             * @return result
             * @since 0.1.0
             */
            return wrapUserFields(result);
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("result", getDefaultResult());
            err.put("error", String.valueOf(e.getMessage()));
            /**
             * wrapUserFields.
             * @param err err
             * @return result
             * @since 0.1.0
             */
            return wrapUserFields(err);
        }
    }

    private Map.Entry<Map<String, Object>, String> getIntentResult(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        if (ComplexIntentState.START.equals(state.status())) {
            Map<String, Object> intentResult;
            TestBridge bridge = presetBridge;
            if (bridge != null) {
                intentResult = bridge.intentResult(inputs);
            } else {
                intentResult = intentDetection.invoke(inputs, session, context);
            }
            if (intentResult == null) {
                intentResult = Map.of();
            }
            state.setIntentResult(intentResult);
            Object classificationId = intentResult.getOrDefault(KEY_CLASSIFICATION_ID, 0);
            String intentBranch = "branch_" + classificationId;
            state.setBranchId(intentBranch);
            return Map.entry(new LinkedHashMap<>(intentResult), intentBranch);
        }
        Object classificationId = state.intentResult().getOrDefault(KEY_CLASSIFICATION_ID, 0);
        return Map.entry(
                new LinkedHashMap<>(state.intentResult()), "branch_" + classificationId);
    }

    private Map.Entry<String, Map<String, Object>> executeBranchWorkflow(
            Map<String, Object> inputs,
            String intentBranch,
            NodeSessionApi session,
            ModelContext context) {
        Map<String, Object> branchConfig = branchesById.get(intentBranch);
        if (branchConfig == null) {
            // fallback: classificationId already a full branch id
            branchConfig = branchesById.get(intentBranch.replaceFirst("^branch_", ""));
            if (branchConfig == null) {
                branchConfig = branchesById.get(str(state.intentResult().get(KEY_CLASSIFICATION_ID)));
            }
        }
        if (branchConfig == null) {
            state.setStatus(ComplexIntentState.END);
            return null;
        }
        Object configs = branchConfig.get("configs");
        if (!(configs instanceof Map<?, ?> cm)) {
            state.setStatus(ComplexIntentState.END);
            return null;
        }
        Object workflowIdObj = cm.get("workflow_id");
        if (workflowIdObj == null) {
            state.setStatus(ComplexIntentState.END);
            return null;
        }
        String workflowId = String.valueOf(workflowIdObj);
        state.setWorkflowId(workflowId);

        Map<String, Object> branchCfg = new LinkedHashMap<>();
        cm.forEach((k, v) -> branchCfg.put(String.valueOf(k), v));
        Map<String, Object> wfResult = invokeSubWorkflow(inputs, workflowId, branchCfg, session, context);
        return Map.entry(workflowId, wfResult == null ? Map.of() : wfResult);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeSubWorkflow(
            Map<String, Object> inputs,
            String workflowId,
            Map<String, Object> branchConfig,
            NodeSessionApi session,
            ModelContext context) {
        Map<String, Object> subInputs = buildSubWorkflowInput(inputs);
        TestBridge bridge = presetBridge;
        if (bridge != null) {
            Map<String, Object> stub = bridge.subWorkflowResult(workflowId, subInputs);
            state.setStatus(ComplexIntentState.END);
            return stub == null ? Map.of() : stub;
        }
        if (subWorkflowResolver == null || nodeTypeRegistry == null || buildContext == null) {
            state.setStatus(ComplexIntentState.END);
            return Map.of();
        }
        try {
            AssembledWorkflow child = subWorkflowResolver.resolve(branchConfig);
            if (child == null) {
                state.setStatus(ComplexIntentState.END);
                return Map.of();
            }
            NodeBuildContext childCtx = buildContext.childDepth();
            NodeTypeRegistry reg =
                    nodeTypeRegistry != null ? nodeTypeRegistry : NodeTypeRegistry.createWithBuiltins();
            Map<String, Object> out =
                    StudioChildWorkflowRunner.invoke(
                            child,
                            nodeId + "-child",
                            reg,
                            childCtx,
                            subInputs,
                            session,
                            context);
            if (isHang(out)) {
                state.setStatus(ComplexIntentState.USER_INTERACT);
            } else {
                state.setStatus(ComplexIntentState.END);
            }
            /**
             * flattenWorkflowResult.
             * @param out out
             * @return result
             * @since 0.1.0
             */
            return flattenWorkflowResult(out);
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            state.setStatus(ComplexIntentState.END);
            throw e;
        }
    }

    private Map<String, Object> getAggregateResult(
            String wfId, Map<String, Object> wfResult, Map<String, Object> intentResult) {
        if (!ComplexIntentState.END.equals(state.status())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("workflow_result", wfResult);
            out.putAll(intentResult);
            return out;
        }
        if (wfId == null || wfResult == null) {
            return intentResult;
        }
        Map<String, Object> aggregated = aggregateOutput(wfId, wfResult);
        Map<String, Object> out = new LinkedHashMap<>(aggregated);
        out.putAll(intentResult);
        return out;
    }

    private Map<String, Object> aggregateOutput(String wfId, Map<String, Object> wfResult) {
        Map<String, Object> branch = wfToBranches.get(wfId);
        if (branch == null) {
            return Map.of();
        }
        String branchId = str(branch.get("id"));
        Map<String, List<List<String>>> groups = branch2group.getOrDefault(branchId, Map.of());
        Map<String, Object> groupResult = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<String>>> e : groups.entrySet()) {
            Object value = null;
            for (List<String> desc : e.getValue()) {
                value = parseValue(wfResult, desc);
                if (value != null) {
                    break;
                }
            }
            groupResult.put(e.getKey(), value);
        }
        return groupResult;
    }

    static Object parseValue(Map<String, Object> result, List<String> descriptions) {
        Object current = result;
        for (String desc : descriptions) {
            if (current instanceof Map<?, ?> m && m.containsKey(desc)) {
                current = m.get(desc);
            } else {
                return null;
            }
        }
        return current;
    }

    static Map<String, Map<String, List<List<String>>>> buildBranchToGroup(Map<String, Object> groups) {
        Map<String, Map<String, List<List<String>>>> branch2group = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : groups.entrySet()) {
            String groupName = e.getKey();
            if (!(e.getValue() instanceof List<?> branches)) {
                continue;
            }
            for (Object branch : branches) {
                String path = String.valueOf(branch).replace("${", "").replace("}", "");
                String[] desc = path.split("\\.");
                if (desc.length >= 2) {
                    String nodeId = desc[0];
                    List<String> rest = new ArrayList<>();
                    for (int i = 1; i < desc.length; i++) {
                        rest.add(desc[i]);
                    }
                    branch2group
                            .computeIfAbsent(nodeId, k -> new LinkedHashMap<>())
                            .computeIfAbsent(groupName, k -> new ArrayList<>())
                            .add(rest);
                }
            }
        }
        return branch2group;
    }

    static Map<String, Object> convertInput(Object inputs) {
        if (inputs instanceof InteractiveInput ii) {
            Map<String, Object> result = new LinkedHashMap<>();
            Object raw = ii.getRawInputs();
            if (raw != null) {
                result.put(INPUT, raw);
            }
            return result;
        }
        if (!(inputs instanceof Map<?, ?> m)) {
            return Map.of(INPUT, inputs);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        m.forEach((k, v) -> map.put(String.valueOf(k), v));
        if (map.containsKey(INPUT)) {
            return map;
        }
        Object uf = map.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> um) {
            Object in = um.get(INPUT);
            Map<String, Object> out = new LinkedHashMap<>(map);
            out.put(INPUT, in);
            return out;
        }
        return map;
    }

    static Map<String, Object> buildSubWorkflowInput(Map<String, Object> inputs) {
        Map<String, Object> sub = deepCopy(inputs);
        if (sub.containsKey("systemFields") && sub.get("systemFields") instanceof Map<?, ?> sm) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sm.forEach((k, v) -> sys.put(String.valueOf(k), v));
            Object query = sub.get(INPUT);
            if (query == null) {
                query = sub.get("query");
            }
            sys.put("query", query == null ? "" : query);
            sub.put("systemFields", sys);
        }
        return sub;
    }

    static Map<String, Object> getDefaultResult() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("result", "分类0");
        d.put("reason", "default");
        d.put(KEY_CLASSIFICATION_ID, 0);
        d.put("name", "其他意图");
        return d;
    }

    private static Map<String, Object> wrapUserFields(Map<String, Object> payload) {
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(USER_FIELDS, payload == null ? Map.of() : payload);
        return wrap;
    }

    private static Map<String, Object> flattenWorkflowResult(Map<String, Object> out) {
        if (out == null) {
            return Map.of();
        }
        Map<String, Object> flat = new LinkedHashMap<>(out);
        Object uf = out.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> um) {
            um.forEach((k, v) -> flat.putIfAbsent(String.valueOf(k), v));
        }
        if (!flat.containsKey(RESPONSE_CONTENT)) {
            Object answer = flat.get("answer");
            if (answer == null && uf instanceof Map<?, ?> u2) {
                answer = u2.get("answer");
            }
            flat.put(RESPONSE_CONTENT, answer == null ? "" : answer);
        }
        return flat;
    }

    private static boolean isHang(Map<String, Object> out) {
        if (out == null) {
        return false;
    }
        Object uf = out.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Object hang = m.get("hangState");
            if ("INPUT_REQUIRED".equalsIgnoreCase(String.valueOf(hang))) {
                return true;
            }
            Object status = m.get("STATUS");
            if ("INPUT_REQUIRED".equalsIgnoreCase(String.valueOf(status))) {
                return true;
            }
        }
        return false;
    }

    private void loadStateFromSession(NodeSessionApi session) {
        if (session == null) {
        return;
    }
        try {
            Object raw = session.getState(ComplexIntentState.STATE_KEY);
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> map = new LinkedHashMap<>();
                m.forEach((k, v) -> map.put(String.valueOf(k), v));
                state = ComplexIntentState.fromMap(map);
            }
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private void storeStateToSession(NodeSessionApi session) {
        if (session == null) {
        return;
    }
        try {
            session.updateState(Map.of(ComplexIntentState.STATE_KEY, state.toMap()));
        } catch (RuntimeException ignored) {
            // mock
        }
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

    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        in.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> nested = new LinkedHashMap<>();
                m.forEach((nk, nv) -> nested.put(String.valueOf(nk), nv));
                out.put(k, nested);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
