/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsubworkflow;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.control.SubRequestScope;
import com.openjiuwen.studio.dsl.util.DeepCopies;
import com.openjiuwen.studio.dsl.util.SanitizeMessage;
import com.openjiuwen.studio.dsl.util.SessionStateIsolator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Python {@code SubWorkflow} helpers from {@code sub_workflow.py} (strict 1:1).
 *
 * <p>Child workflow assembly / Pregel vs linear execution stays in the Handler (host). This engine
 * owns Python-shaped inputs, REQUEST scope, interrupt tree scan, result packaging, and stream chunk
 * classification.
 *
 * @since 2026-08-26
 */

public final class FlowSubWorkflowEngine {
    public static final String USER_FIELDS = "userFields";
    public static final String SYSTEM_FIELDS = "systemFields";
    public static final String PRE_DEFINE_FIELDS = "preDefineFields";
    public static final String GLOBAL_VARIABLES = "global_variables";
    public static final String REQUEST_VARIABLES = "_request";
    public static final String MESSAGE_NODE_END = "message_end";
    public static final String GLOBAL_REF_PREFIX = "MEMORY_VARIABLE.";
    public static final String INTERACTION = "interaction";

    public static final int DEFAULT_SUB_WORKFLOW_TIMEOUT = 300;
    public static final int DEFAULT_STREAM_FRAME_TIMEOUT = 120;
    public static final int DEFAULT_FIRST_FRAME_TIMEOUT = 10;

    public static final Set<String> CHILD_INTERRUPT_STATE_KEYS =
            SessionStateIsolator.CHILD_INTERRUPT_STATE_KEYS;

    private final FlowSubWorkflowConfig config;
    private final SubWorkflowState nodeState = new SubWorkflowState();
    private String interruptChildNodeId;
    private String pendingInteractPrompt = "";
    private boolean lastChildCompleted;
    private Map<String, Object> streamState;
    private List<String> globalVarNames;

    public FlowSubWorkflowEngine(FlowSubWorkflowConfig config) {
        this.config = config == null
                ? FlowSubWorkflowConfig.fromNodeConfigs("", Map.of())
                : config;
    }

    /**
     * config.
     *
     * @return result
     * @since 0.1.0
     */

    public FlowSubWorkflowConfig config() {
        return config;
    }

    /**
     * nodeState.
     *
     * @return result
     * @since 0.1.0
     */

    public SubWorkflowState nodeState() {
        return nodeState;
    }

    /**
     * lastChildCompleted.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean lastChildCompleted() {
        return lastChildCompleted;
    }

    /**
     * setLastChildCompleted.
     *
     * @param v v
     * @since 0.1.0
     */

    public void setLastChildCompleted(boolean v) {
        this.lastChildCompleted = v;
    }

    /**
     * interruptChildNodeId.
     *
     * @return result
     * @since 0.1.0
     */

    public String interruptChildNodeId() {
        return interruptChildNodeId;
    }

    /**
     * setInterruptChildNodeId.
     *
     * @param id id
     * @since 0.1.0
     */

    public void setInterruptChildNodeId(String id) {
        this.interruptChildNodeId = id;
    }

    /**
     * pendingInteractPrompt.
     *
     * @return result
     * @since 0.1.0
     */

    public String pendingInteractPrompt() {
        return pendingInteractPrompt;
    }

    /**
     * setPendingInteractPrompt.
     *
     * @param prompt prompt
     * @since 0.1.0
     */

    public void setPendingInteractPrompt(String prompt) {
        this.pendingInteractPrompt = prompt == null ? "" : prompt;
    }
    public Map<String, Object> streamState() {
        return streamState;
    }

    /**
     * shouldInterrupt.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean shouldInterrupt() {
        return nodeState.shouldInterrupt();
    }

    /**
     * reset.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean reset() {
        nodeState.reset();
        return true;
    }

    /**
     * loadState.
     *
     * @param state state
     * @since 0.1.0
     */

    public void loadState(SubWorkflowState state) {
        if (state != null) {
        nodeState.setStatus(state.status());
    }
    }

    /**
     * errorToOutput.
     *
     * @since 0.1.0
     *
     */

    public void errorToOutput() {
        nodeState.setStatus(SubWorkflowExecutionStatus.END);
    }

    /**
     * * Python {@code _build_invoke_params}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> buildInvokeParams(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> systemFields = mapOf(inputs.get(SYSTEM_FIELDS));
        Map<String, Object> userFields = mapOf(inputs.get(USER_FIELDS));
        Map<String, Object> memoryFields = mapOf(inputs.get("memory"));

        String query = stringOrEmpty(systemFields.get("query"));
        if (interruptChildNodeId != null) {
            String resume = extractParentResumeQuery(session, inputs);
            if (resume != null) {
                query = resume;
            }
        }

        Map<String, Object> globalVariables = new LinkedHashMap<>(readGlobalVariables(session));
        Object userId = globalVariables.get("userId");
        globalVariables.putAll(memoryFields);
        globalVariables.putAll(userFields);
        if (userId != null) {
            globalVariables.put("userId", userId);
        } else if (!globalVariables.containsKey("userId")) {
            globalVariables.put("userId", "");
        }

        List<Map<String, Object>> conversationHistory = new ArrayList<>();
        if (context != null && query != null && !query.isEmpty()) {
            try {
                context.addMessages(new UserMessage(query));
            } catch (RuntimeException ignored) {
                // ModelContext may be mock / read-only
            }
            try {
                List<BaseMessage> msgs = context.getMessages();
                if (msgs != null) {
                    for (BaseMessage m : msgs) {
                        Map<String, Object> dump = new LinkedHashMap<>();
                        if (m != null) {
                            dump.put("role", m.getRole() == null ? "" : String.valueOf(m.getRole()));
                            dump.put("content", m.getContent() == null ? "" : String.valueOf(m.getContent()));
                        }
                        conversationHistory.add(dump);
                    }
                }
            } catch (RuntimeException ignored) {
                // soft
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", query == null ? "" : query);
        params.put(GLOBAL_VARIABLES, globalVariables);
        params.put("conversation_history", conversationHistory);
        return params;
    }

    /**
     * Python {@code _prepare_child_inputs} (dict branch + resume InteractiveInput).
     *
     * @param nestingDepth Studio host depth marker (not in Python; kept for linear chains)
     */

    public Object prepareChildInputs(
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext context,
            Object originalInputs,
            int nestingDepth,
            boolean forceResume) {
        Map<String, Object> current = DeepCopies.map(inputs == null ? Map.of() : inputs);
        boolean resume = forceResume || shouldResumeChildWorkflow(session, originalInputs, current);
        Map<String, Object> params = buildInvokeParams(current, session, context);

        if (resume) {
            nodeState.setStatus(SubWorkflowExecutionStatus.USER_INTERACT);
            String resumeQuery = extractParentResumeQuery(session, originalInputs);
            if (resumeQuery == null) {
                resumeQuery = extractParentResumeQuery(session, current);
            }
            if (resumeQuery == null) {
                resumeQuery = extractFromInteractiveInput(originalInputs);
            }
            if (resumeQuery == null) {
                resumeQuery = stringOrNull(params.get("query"));
            }
            return buildChildInteractiveInput(resumeQuery == null ? "" : resumeQuery);
        }

        Map<String, Object> memoryFields = mapOf(current.get("memory"));
        Map<String, Object> userFields = mapOf(current.get(USER_FIELDS));
        Map<String, Object> childInputs = new LinkedHashMap<>();
        childInputs.putAll(memoryFields);
        childInputs.putAll(userFields);
        childInputs.put("query", params.get("query"));
        childInputs.put(GLOBAL_VARIABLES, params.get(GLOBAL_VARIABLES));
        childInputs.put("conversation_history", params.get("conversation_history"));
        @SuppressWarnings("unchecked")
        Map<String, Object> globals = (Map<String, Object>) params.get(GLOBAL_VARIABLES);
        childInputs.put(SubRequestScope.REQUEST_KEY_ALT, globals);
        childInputs.put(SubRequestScope.REQUEST_KEY, globals);

        // Studio linear merge expects userFields envelope
        Map<String, Object> uf = new LinkedHashMap<>(userFields);
        uf.put("__nestingDepth__", nestingDepth);
        if (globals != null && !globals.isEmpty()) {
            uf.putIfAbsent(SubRequestScope.REQUEST_KEY_ALT, globals);
            childInputs.putIfAbsent(SubRequestScope.REQUEST_KEY_ALT, globals);
        }
        Map<String, Object> envelope = new LinkedHashMap<>(current);
        envelope.putAll(childInputs);
        envelope.put(USER_FIELDS, uf);
        return envelope;
    }

    /**
     * buildChildInteractiveInput.
     *
     * @param userResponse userResponse
     * @return result
     * @since 0.1.0
     */

    public InteractiveInput buildChildInteractiveInput(String userResponse) {
        return new InteractiveInput(userResponse == null ? "" : userResponse);
    }
    public Map<String, Object> enterRequestScope(NodeSessionApi session, Object childInputs) {
        Map<String, Object> asMap =
                childInputs instanceof Map<?, ?> m ? castMap(m) : Map.of();
        return SubRequestScope.enter(session, asMap);
    }

    /**
     * exitRequestScope.
     *
     * @param session session
     * @param parentSnapshot parentSnapshot
     * @since 0.1.0
     */

    public void exitRequestScope(NodeSessionApi session, Map<String, Object> parentSnapshot) {
        SubRequestScope.exit(session, parentSnapshot);
    }

    /**
     * * Python success return shape.
     *
     * @param responseContent responseContent
     * @param userFields userFields
     * @param memory memory
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> packageSuccess(
            String responseContent, Map<String, Object> userFields, Map<String, Object> memory) {
        nodeState.setStatus(SubWorkflowExecutionStatus.END);
        lastChildCompleted = true;
        Map<String, Object> uf = userFields == null ? Map.of() : new LinkedHashMap<>(userFields);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("responseContent", responseContent == null ? "" : responseContent);
        out.put(USER_FIELDS, uf);
        out.put("memory", memory);
        streamState = Map.of(
                "responseContent", out.get("responseContent"),
                USER_FIELDS, uf);
        return out;
    }

    /**
     * * Studio soft-hang envelope (host adaptation when {@code session.interact} is not wired).
     *
     * @param childUf childUf
     * @param nestingDepth nestingDepth
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> packageSoftHang(Map<String, Object> childUf, int nestingDepth) {
        nodeState.setStatus(SubWorkflowExecutionStatus.USER_INTERACT);
        lastChildCompleted = false;
        Map<String, Object> hang = new LinkedHashMap<>(childUf == null ? Map.of() : childUf);
        hang.put("__nestingDepth__", nestingDepth);
        hang.put("nestedWorkflowState", SubWorkflowExecutionStatus.USER_INTERACT.value());
        hang.put("should_interrupt", true);
        if (!hang.containsKey("hangState") || hang.get("hangState") == null) {
            hang.put("hangState", "INPUT_REQUIRED");
        }
        streamState = Map.of(
                "responseContent", String.valueOf(hang.getOrDefault("answer", hang.getOrDefault("result", ""))),
                USER_FIELDS, hang);
        return hang;
    }

    /**
     * Studio linear/Pregel success: Python {@code packageSuccess} plus Studio chain markers inside
     * {@code userFields} so parent merge / resume tests keep working.
     *
     * @param childUf childUf
     * @param nestingDepth nestingDepth
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public Map<String, Object> packageStudioSuccess(
            Map<String, Object> childUf, int nestingDepth, NodeSessionApi session) {
        Map<String, Object> done = new LinkedHashMap<>(childUf == null ? Map.of() : childUf);
        done.put("__nestingDepth__", nestingDepth);
        done.put("nestedWorkflowState", SubWorkflowExecutionStatus.END.value());
        done.put("should_interrupt", false);
        String responseContent =
                String.valueOf(
                        done.getOrDefault(
                                "answer", done.getOrDefault("result", done.getOrDefault("response", ""))));
        return packageSuccess(responseContent, done, collectUpdatedMemory(session));
    }

    /**
     * * Parse child invoke dict — Python {@code _parse_normal_child_invoke_result}.
     *
     * @param result result
     * @return result
     * @since 0.1.0
     */
    public ParsedChildResult parseNormalChildInvokeResult(Map<String, Object> result) {
        String responseContent = stringOrEmpty(result.get("answer"));
        Map<String, Object> userFields = mapOf(result.get("user_fields"));
        if (userFields.isEmpty()) {
            userFields = mapOf(result.get(USER_FIELDS));
        }
        Object stream = result.get("stream");
        if (!(stream instanceof List<?> list) || list.isEmpty()) {
            return new ParsedChildResult(responseContent, userFields);
        }
        Object last = list.get(list.size() - 1);
        if (last instanceof Map<?, ?> lm) {
            Map<String, Object> lastMap = castMap(lm);
            if (INTERACTION.equalsIgnoreCase(String.valueOf(lastMap.get("type")))) {
                return new ParsedChildResult(responseContent, userFields);
            }
            if (responseContent.isEmpty()) {
                responseContent = firstNonBlank(
                        lastMap.get("answer"), lastMap.get("result"), lastMap.get("responseContent"));
            }
            if (userFields.isEmpty()) {
                userFields = mapOf(lastMap.get(USER_FIELDS));
                if (userFields.isEmpty()) {
                    userFields = mapOf(lastMap.get("user_fields"));
                }
            }
        }
        return new ParsedChildResult(responseContent, userFields);
    }

    public Optional<Map<String, Object>> findInteractionChunkInChildResult(Map<String, Object> result) {
        if (result == null) {
            return Optional.empty();
        }
        if (INTERACTION.equalsIgnoreCase(String.valueOf(result.get("type")))) {
            return Optional.of(result);
        }
        Object stream = result.get("stream");
        if (stream instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (item instanceof Map<?, ?> m
                        && INTERACTION.equalsIgnoreCase(String.valueOf(m.get("type")))) {
                    return Optional.of(castMap(m));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * isChildInterruptFields.
     *
     * @param uf uf
     * @return result
     * @since 0.1.0
     */

    public boolean isChildInterruptFields(Map<String, Object> uf) {
        if (uf == null || uf.isEmpty()) {
        return false;
    }
        // Resume completed markers — do not treat as interrupt even if should_interrupt lingered
        if (isResumedAnswer(uf)) {
            return false;
        }
        if (Boolean.TRUE.equals(uf.get("should_interrupt"))) {
            return true;
        }
        Object hang = uf.get("hangState");
        if (hang != null && "INPUT_REQUIRED".equalsIgnoreCase(String.valueOf(hang))) {
            return true;
        }
        Object flowInput = uf.get("flowInputState");
        if (flowInput != null && SubWorkflowExecutionStatus.isUserInteract(flowInput)) {
            return true;
        }
        Object questioner = uf.get("questionerState");
        if (questioner != null && "INPUT_REQUIRED".equalsIgnoreCase(String.valueOf(questioner))) {
            return true;
        }
        return SubWorkflowExecutionStatus.isUserInteract(uf.get("nestedWorkflowState"));
    }

    /**
     * Child uf shows interaction already answered (resume success).
     *
     * @param uf uf
     * @return result
     * @since 0.1.0
     */
    public static boolean isResumedAnswer(Map<String, Object> uf) {
        if (uf == null || uf.isEmpty()) {
        return false;
    }
        Object hang = uf.get("hangState");
        if (hang != null && "Continue".equalsIgnoreCase(String.valueOf(hang))) {
            return true;
        }
        Object questioner = uf.get("questionerState");
        if (questioner != null && "answered".equalsIgnoreCase(String.valueOf(questioner))) {
            return true;
        }
        Object flowInput = uf.get("flowInputState");
        if (flowInput != null
                && ("end".equalsIgnoreCase(String.valueOf(flowInput))
                        || "answered".equalsIgnoreCase(String.valueOf(flowInput)))) {
            return true;
        }
        return false;
    }

    /**
     * True when nested child still needs user input (uf flags or session interrupt keys).
     * Session keys are ignored once uf shows a completed resume answer.
     *
     * @param childUf childUf
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public boolean stillInterrupted(Map<String, Object> childUf, NodeSessionApi session) {
        if (isResumedAnswer(childUf)) {
        return false;
    }
        return isChildInterruptFields(childUf) || detectInterruptInSession(session);
    }

    /**
     * detectInterruptInSession.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public boolean detectInterruptInSession(NodeSessionApi session) {
        InterruptHit hit = detectChildInterrupt(session);
        return hit != null;
    }

    /**
     * detectChildInterrupt.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public InterruptHit detectChildInterrupt(NodeSessionApi session) {
        if (pendingInteractPrompt != null && !pendingInteractPrompt.isEmpty()) {
            String nodeId = interruptChildNodeId != null
                    ? interruptChildNodeId
                    : (session == null ? config.nodeId() : safeComponentId(session));
            return new InterruptHit(nodeId, pendingInteractPrompt);
        }
        interruptChildNodeId = null;
        if (session == null) {
            return null;
        }
        try {
            Map<String, Object> dumped = session.dumpState();
            if (dumped != null) {
                InterruptHit found = findInterruptInStateTree(dumped);
                if (found != null) {
                    interruptChildNodeId = found.nodeId();
                    return found;
                }
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        try {
            Object raw = session.getState(null);
            if (raw instanceof Map<?, ?> sm) {
                InterruptHit matched = matchInterruptInNodeState(safeComponentId(session), castMap(sm));
                if (matched != null) {
                    interruptChildNodeId = matched.nodeId();
                    return matched;
                }
                for (Map.Entry<?, ?> e : sm.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> nm) {
                        InterruptHit m = matchInterruptInNodeState(String.valueOf(e.getKey()), castMap(nm));
                        if (m != null) {
                            interruptChildNodeId = m.nodeId();
                            return m;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return null;
    }

    /**
     * findInterruptInStateTree.
     *
     * @param stateRoot stateRoot
     * @return result
     * @since 0.1.0
     */

    public InterruptHit findInterruptInStateTree(Object stateRoot) {
        if (!(stateRoot instanceof Map<?, ?> root)) {
        return null;
    }
        for (Map.Entry<?, ?> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            InterruptHit matched = matchInterruptInNodeState(String.valueOf(e.getKey()), castMap(value));
            if (matched != null) {
                return matched;
            }
            InterruptHit nested = findInterruptInStateTree(value);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * matchInterruptInNodeState.
     *
     * @param nodeId nodeId
     * @param nodeStateMap nodeStateMap
     * @return result
     * @since 0.1.0
     */

    public InterruptHit matchInterruptInNodeState(String nodeId, Map<String, Object> nodeStateMap) {
        if (nodeStateMap == null) {
        return null;
    }
        if (SubWorkflowExecutionStatus.isUserInteract(nodeStateMap.get("status"))) {
            return new InterruptHit(nodeId, extractInteractPrompt(nodeStateMap));
        }
        for (String stateKey : CHILD_INTERRUPT_STATE_KEYS) {
            Object compState = nodeStateMap.get(stateKey);
            if (compState instanceof Map<?, ?> cm
                    && SubWorkflowExecutionStatus.isUserInteract(cm.get("status"))) {
                Object q = cm.get("question");
                return new InterruptHit(nodeId, q == null ? "" : String.valueOf(q));
            }
        }
        // flat dump: keys themselves are interrupt state maps
        if (CHILD_INTERRUPT_STATE_KEYS.contains(nodeId)
                && SubWorkflowExecutionStatus.isUserInteract(nodeStateMap.get("status"))) {
            Object q = nodeStateMap.get("question");
            return new InterruptHit(nodeId, q == null ? "" : String.valueOf(q));
        }
        return null;
    }

    /**
     * extractInteractPrompt.
     *
     * @param nodeStateMap nodeStateMap
     * @return result
     * @since 0.1.0
     */

    public String extractInteractPrompt(Map<String, Object> nodeStateMap) {
        for (String key : CHILD_INTERRUPT_STATE_KEYS) {
            Object qState = nodeStateMap.get(key);
            if (qState instanceof Map<?, ?> qm) {
                Object prompt = qm.get("question");
                if (prompt != null && !String.valueOf(prompt).isEmpty()) {
                    return String.valueOf(prompt);
                }
            } else if (qState instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        Object q = nodeStateMap.get("question");
        return q == null ? "" : String.valueOf(q);
    }

    /**
     * shouldResumeChildWorkflow.
     *
     * @param session session
     * @param originalInputs originalInputs
     * @param prepared prepared
     * @return result
     * @since 0.1.0
     */

    public boolean shouldResumeChildWorkflow(
            NodeSessionApi session, Object originalInputs, Map<String, Object> prepared) {
        if (nodeState.status() == SubWorkflowExecutionStatus.USER_INTERACT) {
        return true;
    }
        if (detectChildInterrupt(session) != null) {
            return true;
        }
        if (originalInputs instanceof InteractiveInput) {
            return true;
        }
        if (isChildInterruptFields(mapOf(
                prepared == null ? null : prepared.get(USER_FIELDS)))) {
            return true;
        }
        if (prepared != null && Boolean.TRUE.equals(mapOf(prepared.get(USER_FIELDS)).get("__single_debug_recovery__"))) {
            return true;
        }
        return extractParentResumeQuery(session, originalInputs) != null
                || extractParentResumeQuery(session, prepared) != null;
    }

    /**
     * extractParentResumeQuery.
     *
     * @param session session
     * @param inputs inputs
     * @return result
     * @since 0.1.0
     */

    public String extractParentResumeQuery(NodeSessionApi session, Object inputs) {
        String fromInput = extractFromInteractiveInput(inputs);
        if (fromInput != null) {
            return fromInput;
        }
        if (inputs instanceof Map<?, ?> m) {
            Object nested = m.get("interactiveInput");
            fromInput = extractFromInteractiveInput(nested);
            if (fromInput != null) {
                return fromInput;
            }
            Map<String, Object> uf = mapOf(m.get(USER_FIELDS));
            Object q = firstPresent(uf, "query", "response", "userReply", "USER_RESPONSE");
            if (q != null
                    && (Boolean.TRUE.equals(uf.get("__single_debug_recovery__"))
                            || isChildInterruptFields(uf)
                            || detectInterruptInSession(session))) {
                return String.valueOf(q);
            }
        }
        if (session == null) {
            return null;
        }
        try {
            Object wf = session.getState(Constant.INTERACTIVE_INPUT);
            String normalized = normalizeInteractiveStored(wf);
            if (normalized != null) {
                return normalized;
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        try {
            Map<String, Object> dumped = session.dumpState();
            if (dumped != null) {
                String nested = findInteractiveInTree(dumped, interruptChildNodeId);
                if (nested != null) {
                    return nested;
                }
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return null;
    }

    /**
     * extractFromInteractiveInput.
     *
     * @param inputs inputs
     * @return result
     * @since 0.1.0
     */

    public String extractFromInteractiveInput(Object inputs) {
        return extractFromInteractiveInputStatic(inputs);
    }
    static String extractFromInteractiveInputStatic(Object inputs) {
        if (!(inputs instanceof InteractiveInput ii)) {
        return null;
    }
        if (ii.getUserInputs() != null && !ii.getUserInputs().isEmpty()) {
            List<String> keys = List.copyOf(ii.getUserInputs().keySet());
            Object val = ii.getUserInputs().get(keys.get(keys.size() - 1));
            if (val instanceof Map<?, ?> m) {
                Object answer = m.get("answer");
                if (answer == null) {
                    answer = m.get("response");
                }
                if (answer != null) {
                    return String.valueOf(answer);
                }
            }
            if (val != null && !String.valueOf(val).isBlank()) {
                return String.valueOf(val);
            }
        }
        if (ii.getRawInputs() != null) {
            return String.valueOf(ii.getRawInputs());
        }
        return null;
    }

    /**
     * * Python {@code _process_stream_chunk} classification (dict / map frames).
     *
     * @param chunk chunk
     * @return result
     * @since 0.1.0
     */
    public StreamChunkAction processStreamChunk(Object chunk) {
        if (chunk instanceof Map<?, ?> m) {
            Map<String, Object> frame = castMap(m);
            String type = String.valueOf(frame.getOrDefault("type", ""));
            Object dataObj = frame.get("data");
            Map<String, Object> payload =
                    dataObj instanceof Map<?, ?> dm ? castMap(dm) : frame;

            if ("start".equals(type) || "workflow_start".equals(type) || "workflow_end".equals(type)) {
                return StreamChunkAction.skip();
            }
            if (INTERACTION.equalsIgnoreCase(type) || "user_interact".equalsIgnoreCase(type)) {
                nodeState.setStatus(SubWorkflowExecutionStatus.USER_INTERACT);
                Object childNodeId = payload.get("id");
                if (childNodeId == null) {
                    childNodeId = payload.get("node_id");
                }
                if (childNodeId != null) {
                    interruptChildNodeId = String.valueOf(childNodeId);
                }
                Object interactValue = payload.get("value");
                if (interactValue == null) {
                    interactValue = payload.get("prompt");
                }
                if (interactValue != null) {
                    pendingInteractPrompt = String.valueOf(interactValue);
                }
                return StreamChunkAction.interaction(payload);
            }
            if ("error".equals(type)) {
                String rawMsg = firstNonBlank(payload.get("error_message"), payload.get("message"));
                String safe = SanitizeMessage.sanitize(rawMsg);
                throw SubWorkflowException.streamError(safe, null);
            }
            if ("workflow_exception".equals(type)) {
                return StreamChunkAction.abort(payload);
            }
            if ("workflow_final".equals(type)) {
                return StreamChunkAction.finale(mapOf(payload.get("user_fields")));
            }
            if (MESSAGE_NODE_END.equals(type)) {
                if (Boolean.TRUE.equals(payload.get("should_interrupt"))) {
                    pendingInteractPrompt =
                            firstNonBlank(payload.get("answer"), payload.get("result"));
                    if (interruptChildNodeId == null && payload.get("node_id") != null) {
                        interruptChildNodeId = String.valueOf(payload.get("node_id"));
                    }
                }
                return StreamChunkAction.messageEnd();
            }
            if ("end node stream".equals(type) || "partial_content".equals(type)) {
                String content = stringOrEmpty(payload.get("answer"));
                if (Boolean.TRUE.equals(payload.get("should_interrupt"))) {
                    pendingInteractPrompt =
                            firstNonBlank(payload.get("answer"), payload.get("result"));
                    if (interruptChildNodeId == null && payload.get("node_id") != null) {
                        interruptChildNodeId = String.valueOf(payload.get("node_id"));
                    }
                }
                return StreamChunkAction.content(content);
            }
            return StreamChunkAction.passthrough(type, payload);
        }
        return StreamChunkAction.skip();
    }

    public Map<String, Object> collectUpdatedMemory(NodeSessionApi session) {
        if (globalVarNames == null || globalVarNames.isEmpty() || session == null) {
            return null;
        }
        Map<String, Object> updated = new LinkedHashMap<>();
        for (String varName : globalVarNames) {
            Object val = null;
            try {
                val = session.getGlobalState(GLOBAL_REF_PREFIX + varName);
                if (val == null) {
                    val = session.getGlobalState(varName);
                }
            } catch (RuntimeException ignored) {
                // mock
            }
            updated.put(varName, val);
        }
        return updated;
    }

    /**
     * markGraphInterrupt.
     *
     * @param responseContent responseContent
     * @param userFields userFields
     * @since 0.1.0
     */

    public void markGraphInterrupt(String responseContent, Map<String, Object> userFields) {
        lastChildCompleted = false;
        streamState = Map.of(
                "responseContent", responseContent == null ? "" : responseContent,
                USER_FIELDS, userFields == null ? Map.of() : userFields);
    }

    /**
     * traceInterruptMarker.
     *
     * @param session session
     * @param responseContent responseContent
     * @param userFields userFields
     * @since 0.1.0
     */

    public void traceInterruptMarker(NodeSessionApi session, String responseContent, Map<String, Object> userFields) {
        if (session == null) {
        return;
    }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("_sub_interrupt_marker", true);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("responseContent", responseContent == null ? "" : responseContent);
            outputs.put(USER_FIELDS, userFields == null ? Map.of() : userFields);
            data.put("interrupt_outputs", outputs);
            session.trace(data);
        } catch (RuntimeException ignored) {
            // soft like Python
        }
    }

    /**
     * invokeTimeoutSeconds.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public int invokeTimeoutSeconds(NodeSessionApi session) {
        // Python _get_timeout falls back to DEFAULT_FIRST_FRAME_TIMEOUT (not 300)
        Integer env = readEnvTimeout(session, "WORKFLOW_EXECUTE_TIMEOUT");
        if (env != null && env > 0) {
            return env;
        }
        return DEFAULT_FIRST_FRAME_TIMEOUT;
    }

    /**
     * frameTimeoutSeconds.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public int frameTimeoutSeconds(NodeSessionApi session) {
        Integer env = readEnvTimeout(session, "WORKFLOW_STREAM_FRAME_TIMEOUT");
        if (env != null && env > 0) {
            return env;
        }
        return DEFAULT_STREAM_FRAME_TIMEOUT;
    }

    /**
     * firstFrameTimeoutSeconds.
     *
     * @return result
     * @since 0.1.0
     */

    public int firstFrameTimeoutSeconds() {
        return DEFAULT_FIRST_FRAME_TIMEOUT;
    }
    private Integer readEnvTimeout(NodeSessionApi session, String key) {
        if (session == null) {
        return null;
    }
        try {
            Object v = session.getEnv(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                return Integer.parseInt(String.valueOf(v));
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return null;
    }

    private Map<String, Object> readGlobalVariables(NodeSessionApi session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (session == null) {
            return out;
        }
        try {
            Object raw = session.getGlobalState(GLOBAL_VARIABLES);
            if (raw instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        // also fold REQUEST as globals baseline
        out.putAll(SubRequestScope.readRequest(session));
        return out;
    }

    private static String normalizeInteractiveStored(Object value) {
        if (value == null) {
        return null;
    }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            Object last = list.get(list.size() - 1);
            if (last == null) {
                return null;
            }
            if (last instanceof InteractiveInput ii) {
                return extractFromInteractiveInputStatic(ii);
            }
            if (last instanceof Map<?, ?> m) {
                Object content = m.containsKey("content") ? m.get("content") : m.get("answer");
                if (content != null) {
                    return String.valueOf(content);
                }
            }
            return String.valueOf(last);
        }
        if (value instanceof InteractiveInput) {
            return extractFromInteractiveInputStatic(value);
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static String findInteractiveInTree(Object root, String targetNodeId) {
        if (!(root instanceof Map<?, ?> map)) {
        return null;
    }
        if (targetNodeId != null) {
            Object target = map.get(targetNodeId);
            if (target instanceof Map<?, ?> tm && tm.containsKey(Constant.INTERACTIVE_INPUT)) {
                return normalizeInteractiveStored(tm.get(Constant.INTERACTIVE_INPUT));
            }
        }
        if (map.containsKey(Constant.INTERACTIVE_INPUT)) {
            String n = normalizeInteractiveStored(map.get(Constant.INTERACTIVE_INPUT));
            if (n != null) {
                return n;
            }
        }
        for (Object v : map.values()) {
            if (v instanceof Map<?, ?>) {
                String nested = findInteractiveInTree(v, targetNodeId);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String safeComponentId(NodeSessionApi session) {
        try {
            String id = session.getComponentId();
            return id == null ? "" : id;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        return castMap(m);
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
    private static String firstNonBlank(Object... vals) {
        for (Object v : vals) {
            if (v != null && !String.valueOf(v).isBlank()) {
        return String.valueOf(v);
    }
        }
        return "";
    }

    private static Object firstPresent(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) {
        return m.get(k);
    }
        }
        return null;
    }

    /**
     * ParsedChildResult.
     *
     * @param responseContent responseContent
     * @param userFields userFields
     * @return result
     * @since 0.1.0
     */

    public record ParsedChildResult(String responseContent, Map<String, Object> userFields) {}

    /**
     * InterruptHit.
     *
     * @param nodeId nodeId
     * @param prompt prompt
     * @return result
     * @since 0.1.0
     */

    public record InterruptHit(String nodeId, String prompt) {}

    public static final class StreamChunkAction {
        public enum Kind {
            SKIP,
            INTERACTION,
            ABORT,
            FINAL,
            MESSAGE_END,
            CONTENT,
            PASSTHROUGH
        }

        private final Kind kind;
        private final Map<String, Object> payload;
        private final String content;
        private final String type;

        private StreamChunkAction(Kind kind, Map<String, Object> payload, String content, String type) {
            this.kind = kind;
            this.payload = payload;
            this.content = content;
            this.type = type;
        }

        static StreamChunkAction skip() {
            return new StreamChunkAction(Kind.SKIP, Map.of(), null, null);
        }
        static StreamChunkAction interaction(Map<String, Object> payload) {
            return new StreamChunkAction(Kind.INTERACTION, payload, null, INTERACTION);
        }
        static StreamChunkAction abort(Map<String, Object> payload) {
            return new StreamChunkAction(Kind.ABORT, payload, null, "workflow_exception");
        }
        static StreamChunkAction finale(Map<String, Object> userFields) {
            return new StreamChunkAction(Kind.FINAL, userFields, null, "workflow_final");
        }
        static StreamChunkAction messageEnd() {
            return new StreamChunkAction(Kind.MESSAGE_END, Map.of(), null, MESSAGE_NODE_END);
        }
        static StreamChunkAction content(String content) {
            return new StreamChunkAction(Kind.CONTENT, Map.of(), content, "partial_content");
        }
        static StreamChunkAction passthrough(String type, Map<String, Object> payload) {
            return new StreamChunkAction(Kind.PASSTHROUGH, payload, null, type);
        }

        /**
         * kind.
         *
         * @return result
         * @since 0.1.0
         */

        public Kind kind() {
            return kind;
        }
        public Map<String, Object> payload() {
            return payload;
        }

        /**
         * content.
         *
         * @return result
         * @since 0.1.0
         */

        public String content() {
            return content;
        }

        /**
         * type.
         *
         * @return result
         * @since 0.1.0
         */

        public String type() {
            return type;
        }
    }
}
