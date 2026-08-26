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

    public FlowExceptionConfig(String nodeId, String nodeName, String nodeType) {
        this.nodeId = nodeId == null ? "" : nodeId;
        this.nodeName = nodeName == null || nodeName.isBlank() ? this.nodeId : nodeName;
        this.nodeType = nodeType == null || nodeType.isBlank() ? "jiuwen.exception" : nodeType;
    }

    public String nodeId() {
        return nodeId;
    }

    public String nodeName() {
        return nodeName;
    }

    public String nodeType() {
        return nodeType;
    }
}
