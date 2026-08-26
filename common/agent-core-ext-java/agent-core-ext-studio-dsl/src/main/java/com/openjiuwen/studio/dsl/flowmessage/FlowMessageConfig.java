/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code MessageConfig}.
 *
 * @since 2026-08-26
 */
public final class FlowMessageConfig {
    private final String template;
    private final String name;
    private final String outputMode;
    private final String structOutputTemplate;
    private final boolean enableStructMessage;
    private final boolean enableHistory;
    private final Map<String, Object> event;

    FlowMessageConfig(
            String template,
            String name,
            String outputMode,
            String structOutputTemplate,
            boolean enableStructMessage,
            boolean enableHistory,
            Map<String, Object> event) {
        this.template = template == null ? "" : template;
        this.name = name;
        this.outputMode = outputMode;
        this.structOutputTemplate = structOutputTemplate == null ? "" : structOutputTemplate;
        this.enableStructMessage = enableStructMessage;
        this.enableHistory = enableHistory;
        this.event = event == null ? Map.of() : Map.copyOf(event);
    }

    @SuppressWarnings("unchecked")
    public static FlowMessageConfig fromNodeConfigs(Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String template = str(c.get("template"));
        String name = blankToNull(str(c.get("name")));
        Object om = c.getOrDefault("outputMode", c.get("output_mode"));
        String outputMode = om == null ? null : String.valueOf(om);
        String structTpl =
                str(c.getOrDefault("struct_output_template", c.get("structOutputTemplate")));
        boolean structMsg =
                bool(c.get("isStructMessage"))
                        || bool(c.get("enable_struct_message"))
                        || bool(c.get("enableStructMessage"));
        boolean history = normalizeEnableHistory(c.get("enable_history"));
        Map<String, Object> event = new LinkedHashMap<>();
        Object ev = c.get("event");
        if (ev instanceof Map<?, ?> em) {
            em.forEach((k, v) -> event.put(String.valueOf(k), v));
        }
        return new FlowMessageConfig(template, name, outputMode, structTpl, structMsg, history, event);
    }

    public String template() {
        return template;
    }

    public String name() {
        return name;
    }

    public String outputMode() {
        return outputMode;
    }

    public String structOutputTemplate() {
        return structOutputTemplate;
    }

    public boolean enableStructMessage() {
        return enableStructMessage;
    }

    public boolean enableHistory() {
        return enableHistory;
    }

    public Map<String, Object> event() {
        return event;
    }

    public boolean endInterrupt() {
        return "task_completion".equals(String.valueOf(event.get("type")));
    }

    public static boolean normalizeEnableHistory(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return !"false".equalsIgnoreCase(s.trim());
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
