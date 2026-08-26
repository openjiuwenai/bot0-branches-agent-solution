/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.llmchain;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMChain engine — strict 1:1 with Python {@code llm_chain.LLMChain}.
 *
 * @since 2026-08-26
 */
public final class LlmChainEngine {
    private static final String USER_FIELDS = "userFields";
    private static final String PARTIAL_CONTENT = "partial_content";
    private static final String LLM_EXTRA_CONFIGS = "llm_extra_configs";

    /** Test-only LLM stub (mirrors Python patch {@code _create_llm_instance}). */
    private static final ThreadLocal<ModelBridge> TEST_BRIDGE = new ThreadLocal<>();

    /** Invoke + stream bridge. */
    public interface ModelBridge {
        AssistantMessage invoke(List<BaseMessage> messages) throws Exception;

        Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) throws Exception;
    }

    private final String nodeId;
    private LlmChainConfig config;
    private ModelBridge bridge;
    private boolean initialized;
    private Map<String, Object> streamFinalOutput;
    private NodeSessionApi session;
    private ModelContext context;

    public LlmChainEngine(String nodeId) {
        this.nodeId = nodeId;
    }

    LlmChainEngine(String nodeId, LlmChainConfig config, ModelBridge bridge) {
        this.nodeId = nodeId;
        this.config = config;
        this.bridge = bridge;
        this.initialized = bridge != null;
    }

    public static void installTestBridge(ModelBridge bridge) {
        TEST_BRIDGE.set(bridge);
    }

    public static void clearTestBridge() {
        TEST_BRIDGE.remove();
    }

    /** Python {@code init}. */
    public void init(Map<String, Object> conf) {
        this.config = LlmChainConfig.from(nodeId, conf);
        this.initialized = false;
        this.bridge = null;
        this.streamFinalOutput = null;
    }

    public Map<String, Object> getStreamOutput() {
        return streamFinalOutput;
    }

    /** Python {@code invoke}. */
    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        initializeIfNeeded(session);
        this.session = session;
        this.context = context;

        Map<String, Object> inputsData = userFieldsOf(inputs);
        LlmChainPrompt.processInputs(config, inputsData, context);
        LlmChainPrompt.resolveVisionUrls(config, inputsData);

        List<BaseMessage> languageModelInputs =
                LlmChainPrompt.getModelInput(config, inputsData, context, session);

        try {
            AssistantMessage llmOutput = bridge.invoke(languageModelInputs);
            String content = llmOutput.getContent() == null ? "" : String.valueOf(llmOutput.getContent());
            String reasoning = llmOutput.getReasoningContent();
            Map<String, Object> formatted =
                    LlmChainPrompt.formatResponse(config, content, config.responseType(), reasoning);
            Map<String, Object> finalOutput = new LinkedHashMap<>();
            finalOutput.put(USER_FIELDS, formatted);
            appendUsageMetadata(llmOutput.getUsageMetadata(), finalOutput);
            return finalOutput;
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "LLM invoke failed: " + e.getMessage(),
                    e);
        }
    }

    /** Python {@code stream} — returns iterator of frame maps. */
    public Iterator<Object> stream(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        initializeIfNeeded(session);
        this.session = session;
        this.context = context;
        this.streamFinalOutput = null;

        Map<String, Object> inputsData = userFieldsOf(inputs);
        LlmChainPrompt.processInputs(config, inputsData, context);

        String thinkingType = config.thinkingTypeOrNull();
        boolean isEnabled = config.isThinkingEnabled();
        boolean isNone = thinkingType == null;
        boolean outputReasoning = isEnabled || isNone;

        String nodeComponentId = session == null ? nodeId : session.getComponentId();

        if ("json".equals(config.responseType())) {
            Map<String, Object> result = invoke(inputs, session, context);
            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("node_id", nodeComponentId);
            customData.put("node_name", config.nodeName());
            customData.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
            customData.put("componentType", "LLM");
            customData.put("should_interrupt", false);
            customData.put(USER_FIELDS, result.get(USER_FIELDS));
            customData.put("model_stats", result.getOrDefault("metadata", Map.of()));
            customData.put("status", "finish");
            if (outputReasoning) {
                Object uf = result.get(USER_FIELDS);
                if (uf instanceof Map<?, ?> m) {
                    customData.put("think", m.get("reasoning_content"));
                }
            }
            writePartial(session, 0, customData);
            streamFinalOutput = result;
            return List.<Object>of(result).iterator();
        }

        LlmChainPrompt.resolveVisionUrls(config, inputsData);
        List<BaseMessage> languageModelInputs =
                LlmChainPrompt.getModelInput(config, inputsData, context, session);
        List<Map<String, Object>> dictMessages = messagesAsDicts(languageModelInputs);
        LlmChainPrompt.injectRetrievedMemory(dictMessages, inputsData, session);
        // Re-bind if inject mutated (currently no-op) — keep languageModelInputs as-is.

        String outputId = "raw_output";
        List<Map<String, Object>> outputs = config.outputs();
        if (!outputs.isEmpty()) {
            outputId = LlmChainConfig.str(outputs.get(0).getOrDefault("id", "raw_output"));
        }

        try {
            if (isEnabled) {
                return streamThinkingEnabled(languageModelInputs, outputId, nodeComponentId, session);
            }
            return streamRealTime(languageModelInputs, outputId, nodeComponentId, session, outputReasoning);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "LLM stream failed: " + e.getMessage(),
                    e);
        }
    }

    private Iterator<Object> streamThinkingEnabled(
            List<BaseMessage> languageModelInputs,
            String outputId,
            String nodeComponentId,
            NodeSessionApi session)
            throws Exception {
        long start = System.nanoTime();
        List<String> contentChunks = new ArrayList<>();
        StringBuilder reasoning = new StringBuilder();
        UsageMetadata usage = null;
        Long firstTokenMs = null;
        boolean first = true;
        int index = 0;

        Iterator<AssistantMessageChunk> resultIter = bridge.stream(languageModelInputs);
        while (resultIter.hasNext()) {
            AssistantMessageChunk item = resultIter.next();
            if (first) {
                firstTokenMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
                first = false;
            }
            if (item.getUsageMetadata() != null) {
                usage = item.getUsageMetadata();
            }
            if (item.getReasoningContent() != null && !item.getReasoningContent().isBlank()) {
                reasoning.append(item.getReasoningContent());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("answer", "");
                data.put("think", item.getReasoningContent());
                data.put("node_id", nodeComponentId);
                data.put("node_name", config.nodeName());
                data.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
                data.put("should_interrupt", false);
                writePartial(session, index++, data);
            }
            if (item.getContent() != null && !String.valueOf(item.getContent()).isBlank()) {
                contentChunks.add(String.valueOf(item.getContent()));
            }
        }

        long totalTokenMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
        String reasoningContent = reasoning.toString();
        String accumulated = String.join("", contentChunks);
        Map<String, Object> formatted =
                LlmChainPrompt.formatResponse(config, accumulated, config.responseType(), reasoningContent);
        streamFinalOutput = Map.of(USER_FIELDS, formatted);

        List<Object> frames = new ArrayList<>();
        for (String chunk : contentChunks) {
            frames.add(Map.of(USER_FIELDS, Map.of(outputId, chunk)));
        }

        Map<String, Object> modelStats = usage == null ? Map.of() : Map.of();
        Map<String, Object> customData = new LinkedHashMap<>();
        customData.put(
                "rawOutput",
                formatted instanceof Map ? formatted.getOrDefault("rawOutput", accumulated) : accumulated);
        customData.put("node_id", nodeComponentId);
        customData.put("node_name", config.nodeName());
        customData.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
        customData.put("componentType", "LLM");
        customData.put(USER_FIELDS, formatted);
        customData.put("model_stats", modelStats);
        if (formatted.get("reasoning_content") != null) {
            customData.put("think", formatted.get("reasoning_content"));
        }

        trace(session, Map.of(
                "llm_info",
                Map.of(
                        "llm_inputs", languageModelInputs,
                        "llm_outputs", formatted,
                        "reasoning_content", reasoningContent)));
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("first_token<llm>", firstTokenMs);
        perf.put("total_token<llm>", totalTokenMs);
        trace(session, Map.of("performance_metric", perf));
        streamFinalOutput = customData;

        Map<String, Object> finalFrame = new LinkedHashMap<>();
        finalFrame.put(USER_FIELDS, Map.of("reasoning_content", reasoningContent));
        if (usage != null) {
            finalFrame.putAll(usageToMap(usage));
        }
        frames.add(finalFrame);
        return frames.iterator();
    }

    private Iterator<Object> streamRealTime(
            List<BaseMessage> languageModelInputs,
            String outputId,
            String nodeComponentId,
            NodeSessionApi session,
            boolean outputReasoning)
            throws Exception {
        long start = System.nanoTime();
        StringBuilder accumulated = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<String, Object> modelStats = new LinkedHashMap<>();
        UsageMetadata usage = null;
        Long firstTokenMs = null;
        boolean first = true;
        int thinkIndex = 0;

        List<Object> frames = new ArrayList<>();
        Iterator<AssistantMessageChunk> iter = bridge.stream(languageModelInputs);
        while (iter.hasNext()) {
            AssistantMessageChunk chunk = iter.next();
            if (chunk.getUsageMetadata() != null) {
                usage = chunk.getUsageMetadata();
            }
            if (first) {
                firstTokenMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
                first = false;
            }
            if (outputReasoning
                    && chunk.getReasoningContent() != null
                    && !chunk.getReasoningContent().isBlank()) {
                reasoning.append(chunk.getReasoningContent());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("answer", "");
                data.put("think", chunk.getReasoningContent());
                data.put("node_id", nodeComponentId);
                data.put("node_name", config.nodeName());
                data.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
                data.put("componentType", "LLM");
                data.put("should_interrupt", false);
                writePartial(session, thinkIndex++, data);
            }
            if (chunk.getContent() != null && !String.valueOf(chunk.getContent()).isBlank()) {
                String finish = chunk.getFinishReason();
                if (finish == null || "null".equals(finish)) {
                    accumulated.append(chunk.getContent());
                }
                frames.add(Map.of(USER_FIELDS, Map.of(outputId, String.valueOf(chunk.getContent()))));
            }
        }

        long totalTokenMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
        String reasoningForFormat = outputReasoning ? reasoning.toString() : null;
        Map<String, Object> formatted = LlmChainPrompt.formatResponse(
                config, accumulated.toString(), config.responseType(), reasoningForFormat);

        Map<String, Object> customData = new LinkedHashMap<>();
        customData.put("rawOutput", accumulated.toString());
        customData.put("node_id", nodeComponentId);
        customData.put("node_name", config.nodeName());
        customData.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
        customData.put("componentType", "LLM");
        customData.put("should_interrupt", false);
        customData.put(USER_FIELDS, formatted);
        customData.put("model_stats", modelStats);
        customData.put("status", "finish");

        trace(session, Map.of(
                "llm_info",
                Map.of(
                        "llm_inputs", languageModelInputs,
                        "llm_outputs", accumulated.toString(),
                        "reasoning_content", reasoningForFormat == null ? "" : reasoningForFormat)));
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("first_token<llm>", firstTokenMs);
        perf.put("total_token<llm>", totalTokenMs);
        trace(session, Map.of("performance_metric", perf));
        streamFinalOutput = customData;

        Map<String, Object> finalUf = new LinkedHashMap<>();
        finalUf.put("final_output", formatted);
        if (outputReasoning && !reasoning.isEmpty()) {
            finalUf.put("reasoning_content", reasoning.toString());
        }
        Map<String, Object> finalFrame = new LinkedHashMap<>();
        finalFrame.put(USER_FIELDS, finalUf);
        if (usage != null) {
            finalFrame.putAll(usageToMap(usage));
        }
        frames.add(finalFrame);
        return frames.iterator();
    }

    private void initializeIfNeeded(NodeSessionApi session) {
        if (initialized && bridge != null) {
            return;
        }
        if (config == null) {
            throw new NodeExecutionException(
                    nodeId,
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "LLMChain not initialized");
        }
        ModelBridge test = TEST_BRIDGE.get();
        if (test != null) {
            this.bridge = test;
            this.initialized = true;
            return;
        }
        try {
            this.bridge = createDefaultBridge(session);
            this.initialized = true;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Failed to initialize LLM",
                    e);
        }
    }

    private ModelBridge createDefaultBridge(NodeSessionApi session) {
        Map<String, Object> modelMap = resolveModelMap(session);
        ModelClientConfig client = buildClient(config, modelMap);
        ModelRequestConfig request = buildRequest(config);
        Model model = new Model(client, request);
        return new ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) throws Exception {
                return model.invoke(messages, null, null, null, null, null, null, null, null, null);
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) throws Exception {
                return model.stream(messages, List.of(), null, null, null, null, null, null, null, null);
            }
        };
    }

    private static ModelClientConfig buildClient(LlmChainConfig config, Map<String, Object> modelMap) {
        Map<String, Object> ext = config.extension();
        String apiKey = LlmChainConfig.str(ext.get("api_key"));
        String apiBase = LlmChainConfig.str(ext.get("api_base"));
        if (!apiKey.isBlank() && !apiBase.isBlank()) {
            return ModelClientConfig.builder()
                    .clientProvider(config.modelType())
                    .apiKey(apiKey)
                    .apiBase(apiBase)
                    .clientId(config.modelName())
                    .build();
        }
        if (modelMap != null && !modelMap.isEmpty()) {
            Object entry = modelMap.get(config.modelName());
            if (entry instanceof Map<?, ?> m) {
                Map<String, Object> dict = LlmChainConfig.mapOf(m);
                return ModelClientConfig.builder()
                        .clientProvider(LlmChainConfig.str(
                                dict.getOrDefault("client_provider", dict.get("clientProvider"))))
                        .apiKey(LlmChainConfig.str(dict.get("api_key")))
                        .apiBase(LlmChainConfig.str(dict.getOrDefault("api_base", dict.get("apiBase"))))
                        .clientId(LlmChainConfig.str(dict.getOrDefault("client_id", config.modelName())))
                        .build();
            }
        }
        throw new IllegalStateException("Either extension config (api_key, api_base) or model_map is required");
    }

    private static ModelRequestConfig buildRequest(LlmChainConfig config) {
        Map<String, Object> hyper = config.hyperParameters();
        double temp = hyper.get("temperature") instanceof Number n ? n.doubleValue() : 0.1;
        double topP = hyper.get("top_p") instanceof Number n ? n.doubleValue() : 0.15;
        return ModelRequestConfig.builder()
                .modelName(config.modelName())
                .temperature(temp)
                .topP(topP)
                .build();
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
            // mock
        }
        return Map.of();
    }

    private static void appendUsageMetadata(UsageMetadata usage, Map<String, Object> finalOutput) {
        if (usage == null) {
            return;
        }
        finalOutput.putAll(usageToMap(usage));
    }

    private static Map<String, Object> usageToMap(UsageMetadata usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", usage.getCode());
        m.put("err_msg", usage.getErrMsg());
        m.put("prompt", usage.getPrompt());
        m.put("task_id", usage.getTaskId());
        m.put("model_name", usage.getModelName());
        m.put("total_latency", usage.getTotalLatency());
        m.put("first_token_time", usage.getFirstTokenTime());
        m.put("request_start_time", usage.getRequestStartTime());
        m.put("input_tokens", usage.getInputTokens());
        m.put("output_tokens", usage.getOutputTokens());
        m.put("total_tokens", usage.getTotalTokens());
        m.put("cache_tokens", usage.getCacheTokens());
        return m;
    }

    private static void writePartial(NodeSessionApi session, int index, Map<String, Object> data) {
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", PARTIAL_CONTENT);
            envelope.put("index", index);
            envelope.put("data", data);
            session.writeCustomStream(envelope);
        } catch (RuntimeException ignored) {
            // mock session
        }
    }

    private static void trace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
            return;
        }
        try {
            session.trace(data);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    private static List<Map<String, Object>> messagesAsDicts(List<BaseMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BaseMessage m : messages) {
            out.add(LlmChainPrompt.messageToDict(m));
        }
        return out;
    }
}
