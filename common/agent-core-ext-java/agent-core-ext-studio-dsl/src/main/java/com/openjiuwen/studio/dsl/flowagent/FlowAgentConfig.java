/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FlowAgent config — 1:1 with Python {@code FlowAgentConfig}.
 *
 * @since 2026-08-26
 */

public final class FlowAgentConfig {

    /**
     * JIUWEN_AGENT_TYPE.
     *
     * @since 0.1.0
     */

    public static final String JIUWEN_AGENT_TYPE = "jiuwen.agent";

    private final String strategyName;
    private final String strategyProvider;
    private final int maxIteration;
    private final boolean streaming;
    private final boolean withChatHistory;
    private final int chatHistoryMaxTurn;
    private final String systemPrompt;
    private final Map<String, Object> llmConfig;
    private final List<Map<String, Object>> plugins;

    private FlowAgentConfig(
            String strategyName,
            String strategyProvider,
            int maxIteration,
            boolean streaming,
            boolean withChatHistory,
            int chatHistoryMaxTurn,
            String systemPrompt,
            Map<String, Object> llmConfig,
            List<Map<String, Object>> plugins) {
        this.strategyName = strategyName;
        this.strategyProvider = strategyProvider;
        this.maxIteration = maxIteration;
        this.streaming = streaming;
        this.withChatHistory = withChatHistory;
        this.chatHistoryMaxTurn = chatHistoryMaxTurn;
        this.systemPrompt = systemPrompt;
        this.llmConfig = llmConfig;
        this.plugins = plugins;
    }

    /**
     * from.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static FlowAgentConfig from(String nodeId, Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : conf;
        String strategyName = str(first(c, "strategy_name", "strategyName"), "ReAct");
        String strategyProvider = str(first(c, "strategy_provider", "strategyProvider"), "JiuWen");
        int maxIteration = intVal(first(c, "max_iteration", "maxIteration"), 9);
        boolean streaming = boolVal(c.get("streaming"), false);
        boolean withChatHistory = boolVal(first(c, "with_chat_history", "withChatHistory"), true);
        int chatHistoryMaxTurn = intVal(first(c, "chat_history_max_turn", "chatHistoryMaxTurn"), 0);
        String systemPrompt = str(first(c, "system_prompt", "systemPrompt"), "");
        Map<String, Object> llmConfig = mapOf(first(c, "llm_config", "llmConfig"));
        List<Map<String, Object>> plugins = listOfMaps(c.get("plugins"));

        FlowAgentConfig cfg = new FlowAgentConfig(
                strategyName,
                strategyProvider,
                maxIteration,
                streaming,
                withChatHistory,
                chatHistoryMaxTurn,
                systemPrompt,
                llmConfig,
                plugins);
        cfg.validate(nodeId);
        return cfg;
    }

    void validate(String nodeId) {
        if (!"ReAct".equals(strategyName)) {
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_AGENT_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Unsupported strategy: " + strategyName + ", only ReAct is supported");
        }
        if (!"JiuWen".equals(strategyProvider)) {
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_AGENT_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Unsupported strategy provider: " + strategyProvider + ", only JiuWen is supported");
        }
        if (maxIteration < 1) {
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_AGENT_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Invalid max_iteration: " + maxIteration + ", must be >= 1");
        }
    }

    /**
     * strategyName.
     *
     * @return result
     * @since 0.1.0
     */

    public String strategyName() {
        return strategyName;
    }

    /**
     * strategyProvider.
     *
     * @return result
     * @since 0.1.0
     */

    public String strategyProvider() {
        return strategyProvider;
    }

    /**
     * maxIteration.
     *
     * @return result
     * @since 0.1.0
     */

    public int maxIteration() {
        return maxIteration;
    }

    /**
     * streaming.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean streaming() {
        return streaming;
    }

    /**
     * withChatHistory.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean withChatHistory() {
        return withChatHistory;
    }

    /**
     * chatHistoryMaxTurn.
     *
     * @return result
     * @since 0.1.0
     */

    public int chatHistoryMaxTurn() {
        return chatHistoryMaxTurn;
    }

    /**
     * systemPrompt.
     *
     * @return result
     * @since 0.1.0
     */

    public String systemPrompt() {
        return systemPrompt;
    }
    public Map<String, Object> llmConfig() {
        return llmConfig;
    }

    public List<Map<String, Object>> plugins() {
        return plugins;
    }

    private static Object first(Map<String, Object> c, String a, String b) {
        if (c.containsKey(a) && c.get(a) != null) {
        return c.get(a);
    }
        return c.get(b);
    }

    private static String str(Object o, String def) {
        if (o == null) {
        return def;
    }
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) {
        return n.intValue();
    }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean boolVal(Object o, boolean def) {
        if (o instanceof Boolean b) {
        return b;
    }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o));
        }
        return def;
    }

    private static Map<String, Object> mapOf(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    private static List<Map<String, Object>> listOfMaps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(mapOf(m));
            }
        }
        return out;
    }
}
