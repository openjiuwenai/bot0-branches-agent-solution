/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FlowAgent engine — strict 1:1 with Python {@code flow_agent.FlowAgent}.
 *
 * <p>Composes core {@link ReActAgent}; tests inject {@link ReactBridge} via constructor or
 * {@link com.openjiuwen.studio.dsl.exec.StudioEngineTestOverrides}.
 *
 * @since 2026-08-26
 */

public final class FlowAgentEngine {
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Test / host stub for ReAct invoke+stream (mirrors patching ReActAgent).
     */
    public interface ReactBridge {
        Map<String, Object> invoke(Map<String, Object> mappedInputs) throws IOException, InterruptedException;

        Iterator<Object> stream(Map<String, Object> mappedInputs) throws IOException, InterruptedException;
    }

    private final String nodeId;
    private FlowAgentConfig config;
    private final List<Tool> tools = new ArrayList<>();
    private ToolRegistry studioToolRegistry;
    private final ReactBridge presetBridge;
    private ReactBridge bridge;
    private ReActAgent reactAgent;
    private volatile boolean initialized;
    private final Object initLock = new Object();

    /**
     * FlowAgentEngine.
     * @param nodeId nodeId
     * @since 0.1.0
     */
    public FlowAgentEngine(String nodeId) {
        this.nodeId = nodeId == null ? "agent" : nodeId;
        this.presetBridge = null;
    }

    /**
     * FlowAgentEngine.
     * @param nodeId nodeId
     * @param config config
     * @param bridge bridge
     * @since 0.1.0
     */
    public FlowAgentEngine(String nodeId, FlowAgentConfig config, ReactBridge bridge) {
        this.nodeId = nodeId;
        this.config = config;
        config.validate(nodeId);
        this.presetBridge = bridge;
        this.bridge = bridge;
        this.initialized = bridge != null;
    }

    /**
     * Python {@code init} via conf map (IR node configs).
     *
     * @param conf conf
     * @since 0.1.0
     */
    public void init(Map<String, Object> conf) {
        this.config = FlowAgentConfig.from(nodeId, conf);
        this.initialized = presetBridge != null;
        this.bridge = presetBridge;
        this.reactAgent = null;
        this.tools.clear();
    }

    /**
     * setStudioToolRegistry.
     *
     * @param registry registry
     * @since 0.1.0
     */

    public void setStudioToolRegistry(ToolRegistry registry) {
        this.studioToolRegistry = registry;
    }

    /**
     * Python {@code add_tool}.
     *
     * @param tool tool
     * @return result
     * @since 0.1.0
     */
    public FlowAgentEngine addTool(Tool tool) {
        if (tool != null) {
        tools.add(tool);
    }
        return this;
    }

    /**
     * Python {@code add_tools}.
     *
     * @param more more
     * @return result
     * @since 0.1.0
     */
    public FlowAgentEngine addTools(List<Tool> more) {
        if (more != null) {
        tools.addAll(more);
    }
        return this;
    }

    /**
     * tools.
     *
     * @return result
     * @since 0.1.0
     */

    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    /**
     * config.
     *
     * @return result
     * @since 0.1.0
     */

    public FlowAgentConfig config() {
        return config;
    }

    /**
     * isInitialized.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean isInitialized() {
        return initialized;
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
        ensureInitialized();
        try {
            Map<String, Object> mapped = mapInputsToQuery(inputs);
            Map<String, Object> result = bridge != null
                    ? bridge.invoke(mapped)
                    : asMap(reactAgent.invoke(mapped, toSession(session)));
            /**
             * formatInvokeOutput.
             * @param result result
             * @return result
             * @since 0.1.0
             */
            return formatInvokeOutput(result);
        } catch (RuntimeException | IOException | InterruptedException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("output", "Error in FlowAgent execution: " + e.getMessage());
            err.put("result_type", "error");
            return err;
        }
    }

    /**
     * Python {@code stream}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Iterator<Object> stream(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        ensureInitialized();
        try {
            Map<String, Object> mapped = mapInputsToQuery(inputs);
            if (bridge != null) {
                return bridge.stream(mapped);
            }
            return reactAgent.stream(mapped, toSession(session), List.of(StreamMode.OUTPUT));
        } catch (RuntimeException | IOException | InterruptedException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("output", "Error in FlowAgent streaming: " + e.getMessage());
            payload.put("result_type", "error");
            err.put("payload", payload);
            return List.<Object>of(err).iterator();
        }
    }

    /**
     * Python {@code collect} — aggregate last chunk then invoke.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> collect(
            Object inputs, NodeSessionApi session, ModelContext context) {
        ensureInitialized();
        try {
            Object finalInputs = inputs;
            if (inputs instanceof Iterable<?> it) {
                Object last = null;
                int count = 0;
                for (Object chunk : it) {
                    last = chunk;
                    count++;
                }
                finalInputs = count == 1 ? last : last;
            }
            Map<String, Object> mapped = mapInputsToQuery(asMap(finalInputs));
            Object result = bridge != null
                    ? bridge.invoke(mapped)
                    : reactAgent.invoke(mapped, toSession(session));
            /**
             * asMap.
             * @param result result
             * @return result
             * @since 0.1.0
             */
            return asMap(result);
        } catch (RuntimeException | IOException | InterruptedException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("output", "Error in FlowAgent collect: " + e.getMessage());
            err.put("result_type", "error");
            return err;
        }
    }

    void ensureInitialized() {
        if (initialized) {
        return;
    }
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            if (config == null) {
                config = FlowAgentConfig.from(nodeId, Map.of());
            }
            config.validate(nodeId);

            if (presetBridge != null) {
                this.bridge = presetBridge;
                tools.addAll(FlowAgentToolLoader.loadToolsFromPlugins(config.plugins(), studioToolRegistry));
                initialized = true;
                return;
            }

            createReactAgent();
            tools.addAll(FlowAgentToolLoader.loadToolsFromPlugins(config.plugins(), studioToolRegistry));
            registerTools();
            reactAgent.registerRail(new ToolCallLimitRail(config.maxIteration()));
            initialized = true;
        }
    }

    private void createReactAgent() {
        ReActAgentConfig reactConfig = buildReactConfig();
        this.reactAgent = new ReActAgent(AgentCard.builder()
                .id("flow_agent_workflow")
                .name("FlowAgent Workflow Component")
                .description("FlowAgent for workflow execution with plugin tools")
                .build());
        this.reactAgent.configure(reactConfig);
    }

    @SuppressWarnings("unchecked")
    private ReActAgentConfig buildReactConfig() {
        Map<String, Object> modelCfg = config.llmConfig();

        ModelClientConfig modelClientConfig = null;
        Object mcRaw = modelCfg.get("model_client_config");
        if (mcRaw == null) {
            mcRaw = modelCfg.get("modelClientConfig");
        }
        if (mcRaw instanceof Map<?, ?> mc) {
            modelClientConfig = ModelClientConfig.builder()
                    .clientProvider(str(mc.get("client_provider"), str(mc.get("clientProvider"), "openai")))
                    .apiKey(str(mc.get("api_key"), str(mc.get("apiKey"), "")))
                    .apiBase(str(mc.get("api_base"), str(mc.get("apiBase"), "")))
                    .timeout(num(mc.get("timeout"), 60f))
                    .build();
        }

        ModelRequestConfig modelRequestConfig = null;
        Object rcRaw = modelCfg.get("request_config");
        if (rcRaw == null) {
            rcRaw = modelCfg.get("requestConfig");
        }
        if (rcRaw instanceof Map<?, ?> rc) {
            modelRequestConfig = ModelRequestConfig.builder()
                    .modelName(str(rc.get("model_name"), str(rc.get("modelName"), "")))
                    .temperature((double) num(rc.get("temperature"), 0.7))
                    .topP((double) num(rc.get("top_p"), num(rc.get("topP"), 1.0)))
                    .build();
            if (config.streaming()) {
                modelRequestConfig.getExtraFields().put("stream", true);
            }
        }

        int window = config.chatHistoryMaxTurn() > 0 ? config.chatHistoryMaxTurn() : 10;
        ContextEngineConfig contextEngineConfig = ContextEngineConfig.builder()
                .maxContextMessageNum(200)
                .defaultWindowRoundNum(window)
                .build();

        List<Map<String, String>> promptTemplate = new ArrayList<>();
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            promptTemplate.add(Map.of("role", "system", "content", config.systemPrompt()));
        }

        return ReActAgentConfig.builder()
                .modelName(str(modelCfg.get("model_name"), str(modelCfg.get("modelName"), "")))
                .modelProvider(str(modelCfg.get("model_provider"), str(modelCfg.get("modelProvider"), "openai")))
                .apiKey(str(modelCfg.get("api_key"), str(modelCfg.get("apiKey"), "")))
                .apiBase(str(modelCfg.get("api_base"), str(modelCfg.get("apiBase"), "")))
                .maxIterations(config.maxIteration() + 1)
                .promptTemplate(promptTemplate)
                .modelClientConfig(modelClientConfig)
                .modelConfigObj(modelRequestConfig)
                .contextEngineConfig(contextEngineConfig)
                .build();
    }

    private void registerTools() {
        if (tools.isEmpty() || reactAgent == null) {
        return;
    }
        for (Tool tool : tools) {
            try {
                ToolCard card = tool.getCard();
                if (card != null) {
                    reactAgent.getAbilityManager().add(card);
                }
                try {
                    Runner.resourceMgr().addTool(tool, null);
                } catch (RuntimeException ignored) {
                    // soft-fail like Python
                }
            } catch (RuntimeException ignored) {
                // soft-fail
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapInputsToQuery(Object inputs) {
        Map<String, Object> map = asMap(inputs);
        // Prefer userFields if present (Studio IR envelope)
        if (map.containsKey(USER_FIELDS) && map.get(USER_FIELDS) instanceof Map<?, ?>) {
            map = asMap(map.get(USER_FIELDS));
        }
        if (!map.containsKey("query") && !map.isEmpty()) {
            Object first = map.values().iterator().next();
            return Map.of("query", first == null ? "" : first);
        }
        if (map.isEmpty()) {
            return Map.of("query", "");
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> formatInvokeOutput(Map<String, Object> result) {
        Object output = result == null ? Map.of() : result.getOrDefault("output", Map.of());
        if (output instanceof String s) {
            Object parsed = tryLiteralEval(s);
            if (parsed != null) {
                output = parsed;
            }
        }
        Object userOutput;
        if (output instanceof String) {
            userOutput = output;
        } else if (output instanceof Map<?, ?> om) {
            Object data = om.get("data");
            if (data instanceof Map<?, ?> dm) {
                Object nestedResult = dm.get("result");
                userOutput = nestedResult == null ? "" : nestedResult;
            } else {
                userOutput = "";
            }
        } else {
            userOutput = output == null ? "" : output;
        }
        String resultType =
                result == null ? "answer" : String.valueOf(result.getOrDefault("result_type", "answer"));
        Map<String, Object> uf = new LinkedHashMap<>();
        uf.put("output", userOutput);
        uf.put("result_type", resultType);
        return Map.of(USER_FIELDS, uf);
    }

    private static Object tryLiteralEval(String s) {
        String t = s == null ? "" : s.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return null;
        }
        try {
            return MAPPER.readValue(t, new TypeReference<Object>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Session toSession(NodeSessionApi session) {
        if (session == null) {
        return null;
    }
        try {
            return session.getInner();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static String str(Object o, String def) {
        if (o == null) {
        return def;
    }
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    private static float num(Object o, double def) {
        if (o instanceof Number n) {
        return n.floatValue();
    }
        if (o != null) {
            try {
                return Float.parseFloat(String.valueOf(o));
            } catch (NumberFormatException ignored) {
                return (float) def;
            }
        }
        return (float) def;
    }
}
