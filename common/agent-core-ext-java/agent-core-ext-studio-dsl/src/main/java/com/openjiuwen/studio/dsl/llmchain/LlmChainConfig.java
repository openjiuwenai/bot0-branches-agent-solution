/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.llmchain;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed LLMChain IR config (Python {@code LLMChainConfig} + accessors).
 *
 * @since 2026-08-26
 */

public final class LlmChainConfig {
    static final int CHAT_HISTORY_MAX_TURN_DEFAULT = 3;
    static final String JIUWEN_LLM_TYPE = "jiuwen.LLMComponent";

    private final String nodeId;
    private final Map<String, Object> raw;
    private final Map<String, Object> memory;

    LlmChainConfig(String nodeId, Map<String, Object> raw) {
        this.nodeId = nodeId == null ? "" : nodeId;
        this.raw = raw == null ? Map.of() : Map.copyOf(raw);
        this.memory = mapOf(this.raw.get("memory"));
    }

    /**
     * from.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static LlmChainConfig from(String nodeId, Map<String, Object> conf) {
        LlmChainConfig cfg = new LlmChainConfig(nodeId, conf);
        cfg.validate();
        return cfg;
    }

    /**
     * nodeId.
     *
     * @return result
     * @since 0.1.0
     */

    public String nodeId() {
        return nodeId;
    }

    /**
     * nodeName.
     *
     * @return result
     * @since 0.1.0
     */

    public String nodeName() {
        String name = str(raw.get("name"));
        return name.isBlank() ? nodeId : name;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public Map<String, Object> memory() {
        return memory;
    }

    public Map<String, Object> model() {
        return mapOf(raw.get("model"));
    }

    public Map<String, Object> hyperParameters() {
        return mapOf(model().get("hyperParameters"));
    }

    public Map<String, Object> extension() {
        return mapOf(model().get("extension"));
    }

    /**
     * modelName.
     *
     * @return result
     * @since 0.1.0
     */

    public String modelName() {
        return str(first(model(), "modelName", "model_name"));
    }

    /**
     * modelType.
     *
     * @return result
     * @since 0.1.0
     */

    public String modelType() {
        return str(first(model(), "modelType", "model_type"));
    }
    public Map<String, Object> responseFormat() {
        Object rf = raw.get("responseFormat");
        if (rf instanceof Map<?, ?>) {
            return mapOf(rf);
        }
        return Map.of("type", "text");
    }

    /**
     * responseType.
     *
     * @return result
     * @since 0.1.0
     */

    public String responseType() {
        return str(responseFormat().getOrDefault("type", "text"));
    }

    /**
     * enableHistory.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean enableHistory() {
        Object v = raw.get("enableHistory");
        if (v instanceof Boolean b) {
            return b;
        }
        return false;
    }

    /**
     * historySize.
     *
     * @return result
     * @since 0.1.0
     */

    public int historySize() {
        Object v = raw.get("historySize");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return CHAT_HISTORY_MAX_TURN_DEFAULT;
    }

    public List<Map<String, Object>> templateContent() {
        return listOfMaps(raw.get("templateContent"));
    }

    public List<Map<String, Object>> outputs() {
        Map<String, Object> uf = mapOf(raw.get("userFields"));
        return listOfMaps(uf.get("outputs"));
    }

    /**
     * isThinkingEnabled.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean isThinkingEnabled() {
        Map<String, Object> thinking = mapOf(hyperParameters().get("thinking"));
        return "enabled".equals(str(thinking.get("type")));
    }

    /**
     * thinking.type unset/blank → None state; otherwise enabled|disabled.
     *
     * @return result
     * @since 0.1.0
     */

    public String thinkingTypeOrNull() {
        Map<String, Object> thinking = mapOf(hyperParameters().get("thinking"));
        String type = str(thinking.get("type"));
        return type.isBlank() ? null : type;
    }

    /**
     * vlEnable.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean vlEnable() {
        Object v = extension().get("vl_enable");
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(str(v));
    }

    /**
     * userTemplate.
     *
     * @return result
     * @since 0.1.0
     */

    public String userTemplate() {
        for (Map<String, Object> el : templateContent()) {
            if ("user".equals(str(el.get("role")))) {
        return str(el.get("content"));
    }
        }
        throw configError("Failed to retrieve llm template content");
    }

    /**
     * systemTemplateOrNull.
     *
     * @return result
     * @since 0.1.0
     */

    public String systemTemplateOrNull() {
        for (Map<String, Object> el : templateContent()) {
            if ("system".equals(str(el.get("role")))) {
        return str(el.get("content"));
    }
        }
        return null;
    }

    void validate() {
        validateThinkingMode();
        Map<String, Object> responseConfig = responseFormat();
        String type = str(responseConfig.get("type"));
        if (!isThinkingEnabled() && ("markdown".equals(type) || "text".equals(type))) {
            if (outputs().size() != 1) {
                throw configError(
                        "When type in responseFormat is markdown or text, there is only one user-defined output");
            }
        } else if (outputs().size() < 1) {
            throw configError(
                    "When type in responseFormat is set to JSON, at least one user-defined output is required");
        }
        // Required structural fields (Python LLMChainConfig)
        if (modelName().isBlank() || modelType().isBlank()) {
            throw configError("Invalid LLM config: modelName/modelType required");
        }
        if (!raw.containsKey("templateContent")) {
            throw configError("Invalid LLM config: templateContent required");
        }
        if (!raw.containsKey("userFields")) {
            throw configError("Invalid LLM config: userFields required");
        }
    }

    private void validateThinkingMode() {
        Object thinkingRaw = hyperParameters().get("thinking");
        if (thinkingRaw == null) {
            return;
        }
        Map<String, Object> thinking = mapOf(thinkingRaw);
        String type = str(thinking.get("type"));
        if (!type.isBlank() && !"enabled".equals(type) && !"disabled".equals(type)) {
            throw configError("model thinking type is not valid");
        }
    }

    private NodeExecutionException configError(String msg) {
        return new NodeExecutionException(
                nodeId, JIUWEN_LLM_TYPE, NodeCauseCode.NODE_CONFIG_INVALID, msg);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> cast = new LinkedHashMap<>();
                m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                out.add(cast);
            }
        }
        return out;
    }

    static Object first(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
