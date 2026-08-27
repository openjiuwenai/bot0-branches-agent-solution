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

import java.io.IOException;
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

    /**
     * Invoke + stream bridge.
     */
    public interface ModelBridge {
        AssistantMessage invoke(List<BaseMessage> messages) throws Exception;

        Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) throws Exception;
    }

    private final String nodeId;
    private LlmChainConfig config;
    private final ModelBridge presetBridge;
    private ModelBridge bridge;
    private volatile boolean initialized;
    private Map<String, Object> streamFinalOutput;

    public LlmChainEngine(String nodeId) {
        this.nodeId = nodeId;
        this.presetBridge = null;
    }

    public LlmChainEngine(String nodeId, LlmChainConfig config, ModelBridge bridge) {
        this.nodeId = nodeId;
        this.config = config;
        this.presetBridge = bridge;
        this.bridge = bridge;
        this.initialized = bridge != null;
    }

    /**
     * Python {@code init}.
     *
     * @param conf conf
     * @since 0.1.0
     */
    public void init(Map<String, Object> conf) {
        this.config = LlmChainConfig.from(nodeId, conf);
        this.initialized = presetBridge != null;
        this.bridge = presetBridge;
        this.streamFinalOutput = null;
    }

    public Map<String, Object> getStreamOutput() {
        return streamFinalOutput;
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
        initializeIfNeeded(session);

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

    /**
     * Python {@code stream} — returns iterator of frame maps.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Iterator<Object> stream(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        initializeIfNeeded(session);
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
            customData.put("model_stats", modelStatsFromInvokeResult(result));
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
        Map<String, Object> modelStats = new LinkedHashMap<>();
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
                modelStats.putAll(modelStatsFromUsage(usage));
            }
            mergeChunkMetadata(item, modelStats);
            if (item.getReasoningContent() != null && !item.getReasoningContent().isEmpty()) {
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
            if (hasChunkContent(item)) {
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

        Map<String, Object> modelStatsFinal = modelStats.isEmpty() ? Map.of() : Map.copyOf(modelStats);
        Map<String, Object> customData = new LinkedHashMap<>();
        customData.put(
                "rawOutput",
                formatted instanceof Map ? formatted.getOrDefault("rawOutput", accumulated) : accumulated);
        customData.put("node_id", nodeComponentId);
        customData.put("node_name", config.nodeName());
        customData.put("node_type", LlmChainConfig.JIUWEN_LLM_TYPE);
        customData.put("componentType", "LLM");
        customData.put(USER_FIELDS, formatted);
        customData.put("model_stats", modelStatsFinal);
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
        Iterator<AssistantMessageChunk> source = bridge.stream(languageModelInputs);
        return new RealTimeStreamIterator(
                source, languageModelInputs, outputId, nodeComponentId, session, outputReasoning);
    }

    private static boolean hasChunkContent(AssistantMessageChunk chunk) {
        Object content = chunk.getContent();
        if (content == null) {
            return false;
        }
        if (content instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    private static String chunkContent(AssistantMessageChunk chunk) {
        return String.valueOf(chunk.getContent());
    }

    /**
     * Lazy pull iterator — yields content frames as chunks arrive (Python {@code _stream_real_time}).
     */
    private final class RealTimeStreamIterator implements Iterator<Object> {
        private final Iterator<AssistantMessageChunk> source;
        private final List<BaseMessage> languageModelInputs;
        private final String outputId;
        private final String nodeComponentId;
        private final NodeSessionApi session;
        private final boolean outputReasoning;

        private final long start = System.nanoTime();
        private final StringBuilder accumulated = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private UsageMetadata usage;
        private Long firstTokenMs;
        private boolean first = true;
        private int thinkIndex;
        private boolean sourceExhausted;
        private boolean finalFramePending = true;
        private Object nextFrame;
        private final Map<String, Object> modelStats = new LinkedHashMap<>();

        RealTimeStreamIterator(
                Iterator<AssistantMessageChunk> source,
                List<BaseMessage> languageModelInputs,
                String outputId,
                String nodeComponentId,
                NodeSessionApi session,
                boolean outputReasoning) {
            this.source = source;
            this.languageModelInputs = languageModelInputs;
            this.outputId = outputId;
            this.nodeComponentId = nodeComponentId;
            this.session = session;
            this.outputReasoning = outputReasoning;
        }

        /**
         * hasNext.
         *
         * @return result
         * @since 0.1.0
         */

        @Override
        public boolean hasNext() {
            if (nextFrame != null) {
            return true;
        }
            if (!sourceExhausted) {
                pullContentFrame();
                if (nextFrame != null) {
                    return true;
                }
            }
            if (finalFramePending) {
                nextFrame = buildFinalFrame();
                finalFramePending = false;
            }
            return nextFrame != null;
        }

        /**
         * next.
         *
         * @return result
         * @since 0.1.0
         */

        @Override
        public Object next() {
            if (!hasNext()) {
            throw new java.util.NoSuchElementException();
        }
            Object frame = nextFrame;
            nextFrame = null;
            return frame;
        }

        private void pullContentFrame() {
            while (source.hasNext()) {
                AssistantMessageChunk chunk = source.next();
                if (chunk.getUsageMetadata() != null) {
                    usage = chunk.getUsageMetadata();
                    modelStats.putAll(modelStatsFromUsage(usage));
                }
                mergeChunkMetadata(chunk, modelStats);
                if (first) {
                    firstTokenMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
                    first = false;
                }
                if (outputReasoning
                        && chunk.getReasoningContent() != null
                        && !chunk.getReasoningContent().isEmpty()) {
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
                if (hasChunkContent(chunk)) {
                    String finish = chunk.getFinishReason();
                    if (finish == null || "null".equals(finish)) {
                        accumulated.append(chunkContent(chunk));
                    }
                    nextFrame = Map.of(USER_FIELDS, Map.of(outputId, chunkContent(chunk)));
                    return;
                }
            }
            sourceExhausted = true;
        }

        private Object buildFinalFrame() {
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
            customData.put("model_stats", Map.copyOf(modelStats));
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
            return finalFrame;
        }
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
        if (presetBridge != null) {
            this.bridge = presetBridge;
            this.initialized = true;
            return;
        }
        try {
            this.bridge = createDefaultBridge(session);
            this.initialized = true;
        } catch (IllegalArgumentException | IllegalStateException e) {
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

            /**
             * invoke.
             *
             * @param messages messages
             * @return result
             * @since 0.1.0
             */

            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) throws Exception {
                return model.invoke(messages, null, null, null, null, null, null, null, null, null);
            }

            /**
             * stream.
             *
             * @param messages messages
             * @return result
             * @since 0.1.0
             */

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages)
                    throws Exception {
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

    private static Map<String, Object> modelStatsFromInvokeResult(Map<String, Object> invokeResult) {
        if (invokeResult == null) {
            return Map.of();
        }
        Object meta = invokeResult.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static Map<String, Object> modelStatsFromUsage(UsageMetadata usage) {
        if (usage == null) {
            return Map.of();
        }
        // Java UsageMetadata has no model_stats field; Python falls back to {} when absent.
        return Map.of();
    }

    private static void mergeChunkMetadata(AssistantMessageChunk chunk, Map<String, Object> modelStats) {
        if (chunk == null || modelStats == null) {
        return;
    }
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            modelStats.putAll(metadata);
        }
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
