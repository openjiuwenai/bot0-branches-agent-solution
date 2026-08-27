/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowexception;

/**
 * Identity for Python {@code ExceptionInfo} (node_id / node_name / node_type).
 *
 * @since 2026-08-26
 */

public final class FlowExceptionConfig {
    private final String nodeId;
    private final String nodeName;
    private final String nodeType;

    /**
     * FlowExceptionConfig.
     * @param nodeId nodeId
     * @param nodeName nodeName
     * @param nodeType nodeType
     * @since 0.1.0
     */
    public FlowExceptionConfig(String nodeId, String nodeName, String nodeType) {
        this.nodeId = nodeId == null ? "" : nodeId;
        this.nodeName = nodeName == null || nodeName.isBlank() ? this.nodeId : nodeName;
        this.nodeType = nodeType == null || nodeType.isBlank() ? "jiuwen.exception" : nodeType;
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
        return nodeName;
    }

    /**
     * nodeType.
     *
     * @return result
     * @since 0.1.0
     */

    public String nodeType() {
        return nodeType;
    }
}
