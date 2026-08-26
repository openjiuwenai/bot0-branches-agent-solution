/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowcard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code FlowCardConfig}.
 *
 * @since 2026-08-26
 */
public final class FlowCardConfig {
    private final String template;
    private final String name;
    private final String outputMode;
    private final Map<String, Object> event;
    private final String structOutputTemplate;
    private final boolean enableStructMessage;

    FlowCardConfig(
            String template,
            String name,
            String outputMode,
            Map<String, Object> event,
            String structOutputTemplate,
            boolean enableStructMessage) {
        this.template = template;
        this.name = name;
        this.outputMode = outputMode;
        this.event = event == null ? Map.of() : Map.copyOf(event);
        this.structOutputTemplate = structOutputTemplate == null ? "" : structOutputTemplate;
        this.enableStructMessage = enableStructMessage;
    }

    @SuppressWarnings("unchecked")
    public static FlowCardConfig fromNodeConfigs(Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String template = str(c.get("template"));
        if (template.isBlank()) {
            Object card = c.getOrDefault("card", c.get("cardConfig"));
            if (card instanceof Map<?, ?> m && m.get("template") != null) {
                template = str(m.get("template"));
            }
        }
        String name = str(c.getOrDefault("name", "卡片"));
        if (name.isBlank()) {
            name = "卡片";
        }
        Object om = c.getOrDefault("outputMode", c.get("output_mode"));
        String outputMode = om == null ? null : String.valueOf(om);
        Map<String, Object> event = new LinkedHashMap<>();
        Object ev = c.get("event");
        if (ev instanceof Map<?, ?> em) {
            em.forEach((k, v) -> event.put(String.valueOf(k), v));
        }
        String structTpl = str(c.getOrDefault("structOutputTemplate", c.get("struct_output_template")));
        boolean structMsg =
                bool(c.get("isStructMessage"))
                        || bool(c.get("enable_struct_message"))
                        || bool(c.get("enableStructMessage"));
        return new FlowCardConfig(template, name, outputMode, event, structTpl, structMsg);
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

    public Map<String, Object> event() {
        return event;
    }

    public String structOutputTemplate() {
        return structOutputTemplate;
    }

    public boolean enableStructMessage() {
        return enableStructMessage;
    }

    public boolean endInterrupt() {
        if (event.isEmpty()) {
            return false;
        }
        return "task_completion".equals(String.valueOf(event.get("type")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
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
