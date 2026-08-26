/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.conversation.MessageHistorySupport;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extractor component engine — strict 1:1 with Python {@code flow_extractor.Extractor}.
 *
 * @since 2026-08-26
 */
public final class ExtractorEngine {
    private static final String LLM_EXTRA_CONFIGS = "llm_extra_configs";

    /** Test-only LLM stub (mirrors Python {@code patch Extractor._create_llm_instance}). */
    private static final ThreadLocal<ExtractorLlmExtractor.ModelInvoker> TEST_INVOKER = new ThreadLocal<>();

    private final String nodeId;

    private ExtractorLlmExtractor llmExtractor;
    private ExtractorConfig config;
    private Map<String, Object> modelMap = Map.of();
    private String userResponse = "";
    private Map<String, Object> inputs = Map.of();
    private Map<String, Object> extractedKeyFields = new LinkedHashMap<>();

    public ExtractorEngine(String nodeId) {
        this.nodeId = nodeId;
    }

    /** Direct unit-test entry (init + invoke without lazy path). */
    ExtractorEngine(String nodeId, ExtractorConfig config, ExtractorLlmExtractor.ModelInvoker invoker) {
        this.nodeId = nodeId;
        init(config.rawConfigs(), null, invoker);
    }

    public static void installTestInvoker(ExtractorLlmExtractor.ModelInvoker invoker) {
        TEST_INVOKER.set(invoker);
    }

    public static void clearTestInvoker() {
        TEST_INVOKER.remove();
    }

    /**
     * Python {@code init(conf, session, context)} — validates config and wires LLM.
     */
    public void init(Map<String, Object> conf, NodeSessionApi session) {
        init(conf, session, TEST_INVOKER.get());
    }

    private void init(Map<String, Object> conf, NodeSessionApi session, ExtractorLlmExtractor.ModelInvoker testInvoker) {
        ExtractorConfigValidator.checkConfig(nodeId, conf);
        this.config = ExtractorConfig.fromNodeConfigs(conf);
        this.modelMap = resolveModelMap(session);
        this.llmExtractor = createLlmInstance(testInvoker);
        this.extractedKeyFields = new LinkedHashMap<>();
        this.userResponse = "";
        this.inputs = Map.of();
    }

    /**
     * Python {@code invoke} → {@code reply_key_fields} → {@code as_dict}.
     */
    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        if (llmExtractor == null) {
            init(resolveConfig(inputs), session);
        }

        Map<String, Object> inputsData = userFieldsOf(inputs);

        List<Map<String, Object>> convHistory = getLatestChatHistory(context, null);
        userResponse =
                convHistory.isEmpty() ? "" : str(convHistory.get(convHistory.size() - 1).get("content"));

        traceUser(session, userResponse);

        this.inputs = inputsData.isEmpty() ? Map.of() : inputsData;

        Map<String, Object> keyFields = replyKeyFields(context, session);
        return asDict(keyFields);
    }

    private Map<String, Object> replyKeyFields(ModelContext context, NodeSessionApi session) {
        updateKeyFields(context, session);
        return new LinkedHashMap<>(extractedKeyFields);
    }

    private void updateKeyFields(ModelContext context, NodeSessionApi session) {
        List<Map<String, Object>> chatHistory =
                getLatestChatHistory(context, config.chatHistoryMaxRounds());
        Map<String, Object> cur = llmExtractor.extract(userResponse, chatHistory, session);
        extractedKeyFields.clear();
        extractedKeyFields.putAll(filterToKnownFields(cur));
    }

    private Map<String, Object> asDict(Map<String, Object> keyFields) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userResponse != null && !userResponse.isBlank()) {
            result.put("USER_RESPONSE", userResponse);
        }
        String question = config.questionContent();
        if (question != null && !question.isBlank()) {
            result.put("question", question);
        }
        result.putAll(keyFields);
        return result;
    }

    private ExtractorLlmExtractor createLlmInstance(ExtractorLlmExtractor.ModelInvoker testInvoker) {
        if (config.modelName().isBlank() || config.modelType().isBlank()) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.extractor",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "model_name and model_type are required");
        }
        ExtractorLlmExtractor.ModelInvoker invoker = testInvoker;
        if (invoker == null) {
            invoker = ExtractorLlmExtractor.createDefaultInvoker(config, modelMap);
        }
        return new ExtractorLlmExtractor(nodeId, config, invoker, modelMap);
    }

    private Map<String, Object> resolveConfig(Map<String, Object> inputs) {
        if (inputs != null) {
            Object c = inputs.get("config");
            if (c instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        }
        return Map.of();
    }

    /** Python: keep only keys in {@code cn_fields_name} (empty config → no keys). */
    private Map<String, Object> filterToKnownFields(Map<String, Object> extracted) {
        Map<String, Object> out = new LinkedHashMap<>();
        extracted.forEach((k, v) -> {
            if (config.cnFieldsName().containsKey(k)) {
                out.put(k, v);
            }
        });
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
            // test / mock session
        }
        return Map.of();
    }

    /**
     * Python {@code get_latest_chat_history(context, k)} — context messages only.
     */
    private List<Map<String, Object>> getLatestChatHistory(ModelContext context, Integer k) {
        Integer effectiveK = k;
        if (!config.withChatHistory()) {
            effectiveK = 0;
        }

        List<Map<String, Object>> chatHistory = new ArrayList<>();
        if (context != null) {
            for (BaseMessage msg : context.getMessages()) {
                chatHistory.add(MessageHistorySupport.toHistoryTurn(msg));
            }
        }

        return ExtractorLlmExtractor.getLatestKRoundsChat(chatHistory, effectiveK);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        Object uf = inputs.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static void traceUser(NodeSessionApi session, String user) {
        if (session == null) {
            return;
        }
        try {
            session.trace(Map.of("user", user == null ? "" : user));
        } catch (RuntimeException ignored) {
            // mock session
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
