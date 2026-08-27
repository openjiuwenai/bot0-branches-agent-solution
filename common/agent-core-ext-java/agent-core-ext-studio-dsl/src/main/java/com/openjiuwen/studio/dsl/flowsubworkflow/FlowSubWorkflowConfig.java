/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsubworkflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code SubWorkflowConfig} / {@code Reference}.
 *
 * @since 2026-08-26
 */

public final class FlowSubWorkflowConfig {
    private final String nodeId;
    private final String referenceId;
    private final String referencePath;
    private final Map<String, Object> userFields;
    private final Map<String, Object> systemFields;
    private final Map<String, Object> preDefineFields;
    private final Map<String, Object> raw;

    /**
     * FlowSubWorkflowConfig.
     *
     * @param nodeId nodeId
     * @param referenceId referenceId
     * @param referencePath referencePath
     * @param userFields userFields
     * @param systemFields systemFields
     * @param preDefineFields preDefineFields
     * @param raw raw
     * @since 0.1.0
     */

    public FlowSubWorkflowConfig(
            String nodeId,
            String referenceId,
            String referencePath,
            Map<String, Object> userFields,
            Map<String, Object> systemFields,
            Map<String, Object> preDefineFields,
            Map<String, Object> raw) {
        this.nodeId = nodeId == null ? "" : nodeId;
        this.referenceId = referenceId == null ? "" : referenceId;
        this.referencePath = referencePath == null ? "" : referencePath;
        this.userFields = userFields == null ? Map.of() : Map.copyOf(userFields);
        this.systemFields = systemFields == null ? Map.of() : Map.copyOf(systemFields);
        this.preDefineFields = preDefineFields == null ? Map.of() : Map.copyOf(preDefineFields);
        this.raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /**
     * fromNodeConfigs.
     *
     * @param nodeId nodeId
     * @param configs configs
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static FlowSubWorkflowConfig fromNodeConfigs(String nodeId, Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String refId = "";
        String refPath = "";
        Object ref = c.get("reference");
        if (ref instanceof Map<?, ?> rm) {
            Object id = rm.get("id");
            Object path = rm.get("path");
            if (id != null) {
                refId = String.valueOf(id);
            }
            if (path != null) {
                refPath = String.valueOf(path);
            }
        }
        if (refId.isEmpty()) {
            Object wid = c.getOrDefault("workflowId", c.get("workflow_id"));
            if (wid != null) {
                refId = String.valueOf(wid);
            }
        }
        Map<String, Object> uf = mapOrEmpty(c.get("userFields"));
        Map<String, Object> sf = mapOrEmpty(c.get("systemFields"));
        Map<String, Object> pf = mapOrEmpty(c.get("preDefineFields"));
        return new FlowSubWorkflowConfig(nodeId, refId, refPath, uf, sf, pf, new LinkedHashMap<>(c));
    }

    private static Map<String, Object> mapOrEmpty(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
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
     * referenceId.
     *
     * @return result
     * @since 0.1.0
     */

    public String referenceId() {
        return referenceId;
    }

    /**
     * referencePath.
     *
     * @return result
     * @since 0.1.0
     */

    public String referencePath() {
        return referencePath;
    }
    public Map<String, Object> userFields() {
        return userFields;
    }

    public Map<String, Object> systemFields() {
        return systemFields;
    }

    public Map<String, Object> preDefineFields() {
        return preDefineFields;
    }

    public Map<String, Object> raw() {
        return raw;
    }
}
