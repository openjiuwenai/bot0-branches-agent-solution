/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End node configs from Studio IR — Python {@code End.__init__} / conf_dict fields.
 *
 * @since 2026-08-26
 */

public final class FlowEndConfig {
    private final String responseTemplate;
    private final boolean enableStructMessage;
    private final String structOutputTemplate;
    private final String outputMode;
    private final boolean endInterrupt;
    private final String nodeName;
    private final List<Map<String, Object>> userFieldsInputs;
    private final Map<String, Object> raw;

    /**
     * FlowEndConfig.
     *
     * @param responseTemplate responseTemplate
     * @param enableStructMessage enableStructMessage
     * @param structOutputTemplate structOutputTemplate
     * @param outputMode outputMode
     * @param endInterrupt endInterrupt
     * @param nodeName nodeName
     * @param userFieldsInputs userFieldsInputs
     * @param raw raw
     * @since 0.1.0
     */

    public FlowEndConfig(
            String responseTemplate,
            boolean enableStructMessage,
            String structOutputTemplate,
            String outputMode,
            boolean endInterrupt,
            String nodeName,
            List<Map<String, Object>> userFieldsInputs,
            Map<String, Object> raw) {
        this.responseTemplate = responseTemplate == null ? "" : responseTemplate;
        this.enableStructMessage = enableStructMessage;
        this.structOutputTemplate = structOutputTemplate == null ? "" : structOutputTemplate;
        this.outputMode = outputMode;
        this.endInterrupt = endInterrupt;
        this.nodeName = nodeName == null ? "" : nodeName;
        this.userFieldsInputs = userFieldsInputs == null ? List.of() : List.copyOf(userFieldsInputs);
        this.raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /**
     * fromNodeConfigs.
     *
     * @param configs configs
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static FlowEndConfig fromNodeConfigs(Map<String, Object> configs, String nodeId) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String template = firstNonBlank(c.get("responseTemplate"), c.get("template"), c.get("response"));
        boolean struct =
                Boolean.TRUE.equals(c.get("isStructMessage")) || Boolean.TRUE.equals(c.get("enable_struct_message"));
        Object structTpl = c.get("struct_output_template");
        Object mode = c.getOrDefault("outputMode", c.get("output_mode"));
        Object event = c.get("event");
        boolean interrupt = event instanceof Map<?, ?> em && "task_completion".equals(String.valueOf(em.get("type")));
        String name = String.valueOf(c.getOrDefault("name", nodeId == null ? "" : nodeId));
        return new FlowEndConfig(
                template == null ? "" : template,
                struct,
                structTpl == null ? "" : String.valueOf(structTpl),
                mode == null || String.valueOf(mode).isBlank() ? null : String.valueOf(mode),
                interrupt,
                name,
                FlowEndEngine.inputDefsFromConfigs(c),
                new LinkedHashMap<>(c));
    }

    /**
     * responseTemplate.
     *
     * @return result
     * @since 0.1.0
     */

    public String responseTemplate() {
        return responseTemplate;
    }

    /**
     * enableStructMessage.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean enableStructMessage() {
        return enableStructMessage;
    }

    /**
     * structOutputTemplate.
     *
     * @return result
     * @since 0.1.0
     */

    public String structOutputTemplate() {
        return structOutputTemplate;
    }

    /**
     * outputMode.
     *
     * @return result
     * @since 0.1.0
     */

    public String outputMode() {
        return outputMode;
    }

    /**
     * endInterrupt.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean endInterrupt() {
        return endInterrupt;
    }

    /**
     * nodeName.
     *
     * @return result
     * @since 0.1.0
     */

    public String nodeName() {
        return nodeName;
    }
    public List<Map<String, Object>> userFieldsInputs() {
        return userFieldsInputs;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    private static String firstNonBlank(Object... vals) {
        for (Object v : vals) {
            if (v != null && !String.valueOf(v).isBlank()) {
        return String.valueOf(v);
    }
        }
        return null;
    }
}
