/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/**
 * CodeLogicContext for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class CodeLogicContext {
    private final String nodeId;
    private final Map<String, Object> configs;

    /**
     * CodeLogicContext.
     *
     * @param nodeId nodeId
     * @param configs configs
     */
    public CodeLogicContext(String nodeId, Map<String, Object> configs) {
        this.nodeId = nodeId;
        this.configs = configs;
    }

    /**
     * nodeId.
     *
     * @return result
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * configs.
     *
     * @return result
     */
    public Map<String, Object> configs() {
        return configs;
    }
}
