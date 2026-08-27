/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowqa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FlowQA config — 1:1 with Python {@code FlowQAConfig} / {@code QAConfig}.
 *
 * @since 2026-08-26
 */

public final class FlowQaConfig {
    public static final String TYPE = "EI.qa";

    private final String name;
    private final boolean needReply;
    private final String qaStrategy;
    private final List<String> options;
    private final String structOutputTemplate;
    private final boolean enableStructMessage;
    private final boolean enableHistory;
    private final String indexKey;
    private final Map<String, Object> structInputSchemas;

    private FlowQaConfig(
            String name,
            boolean needReply,
            String qaStrategy,
            List<String> options,
            String structOutputTemplate,
            boolean enableStructMessage,
            boolean enableHistory,
            String indexKey,
            Map<String, Object> structInputSchemas) {
        this.name = name;
        this.needReply = needReply;
        this.qaStrategy = qaStrategy;
        this.options = options;
        this.structOutputTemplate = structOutputTemplate;
        this.enableStructMessage = enableStructMessage;
        this.enableHistory = enableHistory;
        this.indexKey = indexKey;
        this.structInputSchemas = structInputSchemas;
    }

    /**
     * from.
     *
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static FlowQaConfig from(Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : conf;
        String name = c.get("name") == null ? null : String.valueOf(c.get("name"));
        boolean needReply = bool(c.get("needReply"), false);
        String qaStrategy = str(first(c, "qaStrategy", "qa_strategy"), "random");
        List<String> options = new ArrayList<>();
        Object rawOpts = c.get("options");
        if (rawOpts instanceof List<?> list) {
            for (Object o : list) {
                options.add(o == null ? "" : String.valueOf(o));
            }
        }
        String structTpl = str(c.get("struct_output_template"), "");
        boolean enableStruct =
                bool(c.get("isStructMessage"), false) || bool(c.get("enable_struct_message"), false);
        boolean enableHistory = bool(c.get("enable_history"), true);
        String indexKey = str(first(c, "index_key", "indexKey"), "index");

        Map<String, Object> explicit = mapOf(c.get("struct_input_schemas"));
        Object nodeInputs = c.get("inputs");
        Object configInputs = null;
        Object uf = c.get("userFields");
        if (uf instanceof Map<?, ?> um) {
            configInputs = um.get("inputs");
        }
        Map<String, Object> schemas =
                StructInputSchemas.buildStructInputSchemas(nodeInputs, configInputs, explicit);

        return new FlowQaConfig(
                name,
                needReply,
                qaStrategy,
                List.copyOf(options),
                structTpl,
                enableStruct,
                enableHistory,
                indexKey,
                schemas);
    }

    /**
     * name.
     *
     * @return result
     * @since 0.1.0
     */

    public String name() {
        return name;
    }

    /**
     * needReply.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean needReply() {
        return needReply;
    }

    /**
     * qaStrategy.
     *
     * @return result
     * @since 0.1.0
     */

    public String qaStrategy() {
        return qaStrategy;
    }

    /**
     * options.
     *
     * @return result
     * @since 0.1.0
     */

    public List<String> options() {
        return options;
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
     * enableStructMessage.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean enableStructMessage() {
        return enableStructMessage;
    }

    /**
     * enableHistory.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean enableHistory() {
        return enableHistory;
    }

    /**
     * indexKey.
     *
     * @return result
     * @since 0.1.0
     */

    public String indexKey() {
        return indexKey;
    }
    public Map<String, Object> structInputSchemas() {
        return structInputSchemas;
    }

    private static Object first(Map<String, Object> c, String a, String b) {
        if (c.containsKey(a) && c.get(a) != null) {
        return c.get(a);
    }
        return c.get(b);
    }

    private static String str(Object v, String d) {
        return v == null ? d : String.valueOf(v);
    }
    private static boolean bool(Object v, boolean d) {
        if (v == null) {
        return d;
    }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }
}
