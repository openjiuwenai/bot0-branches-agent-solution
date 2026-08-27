/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowstart;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Start node configs from Studio IR — Python {@code Start.__init__} conf_dict.
 *
 * @since 2026-08-26
 */

public final class FlowStartConfig {
    private final Map<String, Object> raw;
    private final String nodeName;

    /**
     * FlowStartConfig.
     * @param raw raw
     * @param nodeName nodeName
     * @since 0.1.0
     */
    public FlowStartConfig(Map<String, Object> raw, String nodeName) {
        this.raw = raw == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(raw));
        this.nodeName = nodeName;
    }

    /**
     * fromNodeConfigs.
     *
     * @param configs configs
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static FlowStartConfig fromNodeConfigs(Map<String, Object> configs, String nodeId) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        Object name = c.get("name");
        return new FlowStartConfig(c, name == null ? nodeId : String.valueOf(name));
    }

    public Map<String, Object> raw() {
        return raw;
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
}
