/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.BranchRouter;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.llm.MessageHistorySupport;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IntentDetection component — strict 1:1 with Python {@code intent_detection.IntentDetection}.
 *
 * @since 2026-08-26
 */
public final class IntentDetectionEngine {
    private static final String INPUT = "input";
    private static final String LLM_EXTRA_CONFIGS = "llm_extra_configs";
    private static final String WORKFLOW_GLOBAL_INTENTS = "global_intents";
    private static final String WORKFLOW_CHAT_HISTORY = "workflow_chat_history";
    private static final String MEMORY_MESSAGE = "memory_message";
    private static final String USER_PROFILE_KEY = "userProfile";
    private static final String ENABLE_KEY = "enable";

    /** Test-only LLM stub (mirrors Python {@code patch IntentDetection._get_llm_instance}). */
    private static final ThreadLocal<IntentDetectionLlmDetector.ModelInvoker> TEST_INVOKER = new ThreadLocal<>();

    private final String nodeId;
    private final Map<String, Object> llmConf;
    private final Map<String, Object> faqConfig;
    private final Map<String, Object> memConf;
    private final ToolRegistry toolRegistry;
    private final BranchRouter router;
    private final boolean hasBranch0;
    private final IntentDetectionLlmDetector.ModelInvoker testInvoker;

    private IntentDetectionConfig intentConfig;
    private final IntentDetectionConfig intentConfigRetry;
    private IntentDetectionState nodeState = new IntentDetectionState();
    private final IntentDetectionState nodeStateRetry = new IntentDetectionState();

    private String fewShotExample = "";
    private String conversationId = "";
    private NodeSessionApi session;
    private Map<String, Object> modelMap = Map.of();
    private boolean enableKnowledge;

    public IntentDetectionEngine(String nodeId, Map<String, Object> nodeConfigs) {
        this(nodeId, nodeConfigs, null, null);
    }

    public IntentDetectionEngine(String nodeId, Map<String, Object> nodeConfigs, ToolRegistry toolRegistry) {
        this(nodeId, nodeConfigs, null, toolRegistry);
    }

    public IntentDetectionEngine(
            String nodeId,
            Map<String, Object> nodeConfigs,
            IntentDetectionLlmDetector.ModelInvoker modelInvoker,
            ToolRegistry toolRegistry) {
        this(
                nodeId,
                IntentDetectionConfig.fromNodeConfigs(nodeConfigs),
                nodeConfigs == null ? Map.of() : nodeConfigs,
                modelInvoker,
                toolRegistry);
    }

    IntentDetectionEngine(
            String nodeId,
            IntentDetectionConfig config,
            Map<String, Object> rawConfigs,
            IntentDetectionLlmDetector.ModelInvoker modelInvoker,
            ToolRegistry toolRegistry) {
        this.nodeId = nodeId;
        Map<String, Object> conf = rawConfigs == null ? Map.of() : rawConfigs;
        this.llmConf = conf;
        this.faqConfig = config.kgConfig();
        this.memConf = mapOf(conf.get("memory"));
        this.toolRegistry = toolRegistry;
        this.router = new BranchRouter();
        this.hasBranch0 =
                config.branches().stream().anyMatch(b -> "branch_0".equals(str(b.get("id"))));
        this.intentConfig = config;
        this.intentConfigRetry = config;
        this.enableKnowledge = config.enableKnowledge();
        this.testInvoker = modelInvoker;
        getKgInstance(faqConfig);
    }

    public static void installTestInvoker(IntentDetectionLlmDetector.ModelInvoker invoker) {
        TEST_INVOKER.set(invoker);
    }

    public static void clearTestInvoker() {
        TEST_INVOKER.remove();
    }

    // --------------------------------------------------------------------------
    // State management (Python reset / get_state / load_state)
    // --------------------------------------------------------------------------

    public boolean reset() {
        this.intentConfig = intentConfigRetry;
        this.nodeState = nodeStateRetry.copy();
        this.fewShotExample = "";
        if (!hasBranch0) {
            this.intentConfig =
                    IntentDetectionConfigFormatter.withDefaultClass(
                            intentConfig, IntentDetectionConfig.DEFAULT_CLASS_ID_LEGACY);
        }
        return true;
    }

    public IntentDetectionState getState() {
        return nodeState;
    }

    public void loadState(IntentDetectionState state) {
        this.nodeState = state == null ? new IntentDetectionState() : state.copy();
    }

    // --------------------------------------------------------------------------
    // Branch router (Python add_branch / router / add_component)
    // --------------------------------------------------------------------------

    public void addBranch(Object condition, Object target, String branchId) {
        router.addBranch(condition, target, branchId);
    }

    public BranchRouter router() {
        return router;
    }

    // --------------------------------------------------------------------------
    // Main entry (Python invoke)
    // --------------------------------------------------------------------------

    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        long startTime = System.nanoTime();
        this.session = session;
        this.conversationId = session == null ? "" : session.getSessionId();
        router.setSession(session);
        modelMap = resolveModelMap(session);

        List<Map<String, Object>> chatHistory = getChatHistory(context, session);
        Map<String, Object> currentInputs = new LinkedHashMap<>();
        Map<String, Object> globalIntentMap = new LinkedHashMap<>();
        String intentClass = intentConfig.defaultClass();

        try {
            currentInputs = prepareDetectionInputs(inputs, chatHistory, session, globalIntentMap);
            globalIntentMap = mapOf(currentInputs.remove("global_intent_map"));
        } catch (Exception e) {
            raiseInputError(e.getMessage());
        }

        if (enableKnowledge) {
            try {
                intentClass = getFaqResult(currentInputs, chatHistory);
            } catch (Exception e) {
                throw new NodeExecutionException(
                        nodeId,
                        "jiuwen.intentDetection",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "intent detection llm invoke error: Search is wrong",
                        e);
            }
        }

        if (!intentClass.equals(intentConfig.defaultClass())) {
            Map<String, Object> idName = intentIdName(intentClass);
            return output(
                    intentClass,
                    "",
                    idName.get("classificationId"),
                    str(idName.get("name")));
        }

        String llmOutput = getLlmResult(currentInputs);
        Map<String, Object> intentRes = handleDetectionResult(llmOutput, globalIntentMap);

        long durationMs = Math.round((System.nanoTime() - startTime) / 1_000_000.0);
        tracePerformance(durationMs);
        return intentRes;
    }

    // --------------------------------------------------------------------------
    // FAQ / knowledge (Python get_faq_result / get_kg_instance)
    // --------------------------------------------------------------------------

    private void getKgInstance(Map<String, Object> conf) {
        if (!enableKnowledge) {
            return;
        }
        String apiId = str(conf.get("apiId"));
        if (apiId.isBlank()) {
            apiId = str(conf.get("id"));
            if (apiId.isBlank() || toolRegistry == null || toolRegistry.find(apiId).isEmpty()) {
                enableKnowledge = false;
            }
        } else if (toolRegistry == null || toolRegistry.find(apiId).isEmpty()) {
            enableKnowledge = false;
        }
    }

    private String getFaqResult(Map<String, Object> currentInputs, List<Map<String, Object>> chatHistory) {
        String query = str(currentInputs.get(INPUT));
        IntentFaqMatcher.FaqMatchResult faq =
                IntentFaqMatcher.match(intentConfig, toolRegistry, query, chatHistory, enableKnowledge);
        String fewShot = faq.fewShotExample();
        currentInputs.put("example_content", fewShot);
        fewShotExample = fewShotExample + "\n" + fewShot;
        return faq.intentClass();
    }

    // --------------------------------------------------------------------------
    // LLM (Python get_llm_result)
    // --------------------------------------------------------------------------

    private String getLlmResult(Map<String, Object> currentInputs) {
        IntentDetectionLlmDetector.ModelInvoker invoker = resolveInvoker();
        if (invoker == null && !intentConfig.hasModelWiring()) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.intentDetection",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "intent detection llm init error: model wiring is required");
        }
        IntentDetectionLlmDetector detector =
                new IntentDetectionLlmDetector(nodeId, intentConfig, invoker, modelMap);

        String chatHistory = str(currentInputs.get("chat_history"));
        String categoryInfo = str(currentInputs.get("category_info"));
        String input = str(currentInputs.get(INPUT));
        String example = str(currentInputs.get("example_content"));
        if (example.isBlank()) {
            example = String.join("\n\n", intentConfig.exampleContent());
        }

        return detector.invokeLlm(
                input,
                chatHistory,
                categoryInfo,
                example,
                memConf,
                session);
    }

    private IntentDetectionLlmDetector.ModelInvoker resolveInvoker() {
        if (testInvoker != null) {
            return testInvoker;
        }
        return TEST_INVOKER.get();
    }

    // --------------------------------------------------------------------------
    // Global intents (Python add_global_intents / _handle_global_intent)
    // --------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String addGlobalIntents(List<?> globalIntents, Map<String, Object> globalIntentMap) {
        String categoryInfo = intentConfig.categoryInfo();
        if (globalIntents == null || globalIntents.isEmpty()) {
            return categoryInfo;
        }

        List<String> categoryInfoList = new ArrayList<>();
        if (!categoryInfo.isBlank()) {
            for (String line : categoryInfo.split("\n")) {
                if (!line.isBlank()) {
                    categoryInfoList.add(line);
                }
            }
        }

        int otherIndex = -1;
        for (int i = 0; i < categoryInfoList.size(); i++) {
            if (categoryInfoList.get(i).contains("其他")) {
                otherIndex = i;
                break;
            }
        }
        boolean otherInLocalDetection = otherIndex > -1;

        if (intentConfig.overridable() && otherInLocalDetection) {
            for (Object item : globalIntents) {
                Map<String, Object> intent = asIntentMap(item);
                String name = str(intent.get("name"));
                if (name.contains("其他")) {
                    String desc = str(intent.get("description"));
                    if (!desc.isBlank()) {
                        String categoryName = "分类" + otherIndex;
                        categoryInfoList.set(otherIndex, categoryName + ": " + desc);
                        globalIntentMap.put(categoryName, intent);
                    }
                    break;
                }
            }
        }

        int curIndex = categoryInfo.contains("分类0")
                ? categoryInfoList.size() - 1
                : categoryInfoList.size();

        for (Object item : globalIntents) {
            Map<String, Object> intent = asIntentMap(item);
            String name = str(intent.get("name"));
            String desc = str(intent.get("description"));
            if (desc.isBlank() || (!name.isBlank() && categoryInfo.contains(name))) {
                continue;
            }
            String categoryName = "分类" + (curIndex + 1);
            categoryInfoList.add(categoryName + ": " + desc);
            curIndex++;
            globalIntentMap.put(categoryName, intent);
        }

        return String.join("\n", categoryInfoList);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleDetectionResult(String llmOutput, Map<String, Object> globalIntentMap) {
        String[] parsed = IntentDetectionLlmDetector.postProcess(intentConfig, llmOutput);
        String intentClass = parsed[0];
        String reason = parsed[1];

        if (globalIntentMap.containsKey(intentClass)) {
            return handleGlobalIntent(intentClass, reason, globalIntentMap.get(intentClass));
        }
        if ("分类0".equals(intentClass)
                && intentConfig.overridable()
                && !globalIntentMap.isEmpty()) {
            return handleGlobalOtherIntent();
        }

        if (!intentConfig.categoryList().contains(intentClass)) {
            Map<String, Object> idName = intentIdName(intentConfig.defaultClass());
            return output(
                    intentConfig.defaultClass(),
                    String.format(
                            IntentDetectionLlmDetector.VALIDATION_FAIL_REASON,
                            intentClass,
                            intentConfig.categoryList()),
                    idName.get("classificationId"),
                    str(idName.get("name")));
        }

        nodeState.setStatus(IntentDetectionState.ExecutionStatus.END);
        Map<String, Object> idName = intentIdName(intentClass);
        return output(intentClass, reason, idName.get("classificationId"), str(idName.get("name")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleGlobalIntent(
            String intentClass, String reason, Object matchedIntent) {
        Map<String, Object> intent = new LinkedHashMap<>();
        if (matchedIntent instanceof Map<?, ?> m) {
            m.forEach((k, v) -> intent.put(String.valueOf(k), v));
        } else {
            intent.put("intent_id", String.valueOf(matchedIntent));
        }
        intent.putIfAbsent("reason", reason);
        nodeState.setStatus(IntentDetectionState.ExecutionStatus.USER_INTERACT);
        if (session == null) {
            return Map.of("type", "GLOBAL_INTENT", "intent", intent);
        }
        return session.interact(Map.of("type", "GLOBAL_INTENT", "intent", intent));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleGlobalOtherIntent() {
        Map<String, Object> intent = Map.of("intent_id", "0", "reason", "其他");
        nodeState.setStatus(IntentDetectionState.ExecutionStatus.USER_INTERACT);
        if (session == null) {
            return Map.of("type", "GLOBAL_INTENT", "intent", intent);
        }
        return session.interact(Map.of("type", "GLOBAL_INTENT", "intent", intent));
    }

    // --------------------------------------------------------------------------
    // Input preparation (Python _prepare_detection_inputs / _get_chat_history)
    // --------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareDetectionInputs(
            Map<String, Object> inputs,
            List<Map<String, Object>> chatHistory,
            NodeSessionApi session,
            Map<String, Object> globalIntentMap) {
        Map<String, Object> currentInputs = new LinkedHashMap<>();

        String categoryInfo = intentConfig.categoryInfo();
        Object globalIntents = getWorkflowParam(session, WORKFLOW_GLOBAL_INTENTS);
        if (globalIntents instanceof List<?> list) {
            categoryInfo = addGlobalIntents(list, globalIntentMap);
        }

        currentInputs.put("user_prompt", intentConfig.userPrompt());
        currentInputs.put("category_info", categoryInfo);
        currentInputs.put("default_class", intentConfig.defaultClass());
        currentInputs.put("enable_history", intentConfig.enableHistory());
        currentInputs.put("enable_input", intentConfig.enableInput());
        currentInputs.put("example_content", String.join("\n\n", intentConfig.exampleContent()));
        currentInputs.put("chat_history_max_turn", intentConfig.chatHistoryMaxTurn());

        if (!intentConfig.enableHistory() && !intentConfig.enableInput()) {
            throw new IllegalArgumentException(
                    "AT LEAST ONE OF INTENT_DETECTION'S ENABLE_HISTORY AND ENABLE_INPUT SHOULD ENABLE.");
        }

        if (intentConfig.enableHistory()) {
            currentInputs.put("chat_history", IntentDetectionLlmDetector.formatChatHistory(
                    chatHistory, intentConfig.chatHistoryMaxTurn()));
        }

        if (intentConfig.enableInput()) {
            currentInputs.put(INPUT, inputs == null ? null : inputs.get(INPUT));
        }

        currentInputs.put("global_intent_map", globalIntentMap);
        return currentInputs;
    }

    private List<Map<String, Object>> getChatHistory(ModelContext context, NodeSessionApi session) {
        List<Map<String, Object>> messages = new ArrayList<>();

        if (intentConfig.enableHistory() && context != null) {
            try {
                for (BaseMessage msg : context.getMessages()) {
                    messages.add(MessageHistorySupport.toHistoryTurn(msg));
                }
            } catch (RuntimeException ignored) {
                // Python logs and continues
            }
        }

        if (messages.isEmpty() && session != null) {
            try {
                Object raw = session.getGlobalState(WORKFLOW_CHAT_HISTORY);
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            Map<String, Object> turn = new LinkedHashMap<>();
                            turn.put("role", str(m.get("role")));
                            turn.put("content", str(m.get("content")));
                            turn.put("enable_history", true);
                            messages.add(turn);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // mock session
            }
        }
        return messages;
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private Map<String, Object> intentIdName(String intentClass) {
        Map<String, Object> intentRes = new LinkedHashMap<>();
        intentRes.put("classificationId", intentConfig.defaultClass());
        intentRes.put("name", IntentDetectionConfig.DEFAULT_CLASS_NAME);

        for (int i = 0; i < intentConfig.categoryList().size(); i++) {
            if (intentConfig.categoryList().get(i).equals(intentClass)) {
                int idx = IntentDetectionLlmDetector.classificationIndex(intentClass);
                intentRes.put("classificationId", idx);
                if (i < intentConfig.categoryNameList().size()) {
                    intentRes.put("name", intentConfig.categoryNameList().get(i));
                }
                break;
            }
        }
        return intentRes;
    }

    private static Map<String, Object> output(Object result, String reason, Object classificationId, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("reason", reason == null ? "" : reason);
        out.put("classificationId", classificationId);
        out.put("name", name == null ? "" : name);
        return out;
    }

    private void raiseInputError(String errorMsg) {
        throw new NodeExecutionException(
                nodeId,
                "jiuwen.intentDetection",
                NodeCauseCode.NODE_INVOKE_FAILED,
                "intent detection user input error, reason: " + errorMsg);
    }

    private void tracePerformance(long durationMs) {
        if (session == null) {
            return;
        }
        try {
            session.trace(Map.of("performance_metric", Map.of("intent llm cost", durationMs)));
        } catch (RuntimeException ignored) {
            // mock session
        }
    }

    private static Object getWorkflowParam(NodeSessionApi session, String key) {
        if (session == null) {
            return null;
        }
        try {
            Object value = session.getGlobalState(key);
            if (value != null) {
                return value;
            }
        } catch (RuntimeException ignored) {
            // mock session
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asIntentMap(Object item) {
        if (item instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of("name", String.valueOf(item));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveModelMap(NodeSessionApi session) {
        if (session == null) {
            return Map.of();
        }
        try {
            Object raw = session.getGlobalState(LLM_EXTRA_CONFIGS);
            if (raw instanceof Map<?, ?> m) {
                Object map = m.get("model_map");
                if (map instanceof Map<?, ?> mm) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    mm.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
            }
        } catch (RuntimeException ignored) {
            // mock session
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
