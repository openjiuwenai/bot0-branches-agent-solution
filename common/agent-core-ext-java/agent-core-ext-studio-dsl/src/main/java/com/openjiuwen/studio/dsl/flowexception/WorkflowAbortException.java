/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowexception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Python {@code WorkflowAbortException} — terminates the workflow from ExceptionInfo.
 *
 * @since 2026-08-26
 */

public final class WorkflowAbortException extends RuntimeException {

    /**
     * Python {@code WORKFLOW_EXCEPTION_END_ERROR[0]}.
     */
    public static final int ERROR_CODE = 101924;

    /**
     * Python {@code WORKFLOW_EXCEPTION_END_ERROR[1]}.
     */
    public static final String ERROR_MESSAGE =
            "The workflow anomaly termination component terminates execution";

    private final Map<String, Object> data;
    private final String nodeId;
    private final String nodeName;
    private final String nodeType;

    /**
     * WorkflowAbortException.
     * @param data data
     * @param nodeId nodeId
     * @param nodeName nodeName
     * @param nodeType nodeType
     * @since 0.1.0
     */
    public WorkflowAbortException(
            Map<String, Object> data, String nodeId, String nodeName, String nodeType) {
        super(ERROR_MESSAGE);
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data));
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
    }

    /**
     * errorCode.
     *
     * @return result
     * @since 0.1.0
     */

    public int errorCode() {
        return ERROR_CODE;
    }
    public Map<String, Object> data() {
        return data;
    }

    public Map<String, Object> toPayload() {
        return data;
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

    /**
     * toString.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String toString() {
        return "WorkflowAbortException{errorCode="
                + ERROR_CODE
                + ", message="
                + ERROR_MESSAGE
                + ", nodeId="
                + nodeId
                + '}';
    }

    /**
     * equals.
     *
     * @param o o
     * @return result
     * @since 0.1.0
     */

    @Override
    public boolean equals(Object o) {
        if (this == o) {
        return true;
    }
        if (!(o instanceof WorkflowAbortException that)) {
            return false;
        }
        return Objects.equals(data, that.data)
                && Objects.equals(nodeId, that.nodeId)
                && Objects.equals(nodeName, that.nodeName)
                && Objects.equals(nodeType, that.nodeType);
    }

    /**
     * hashCode.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public int hashCode() {
        return Objects.hash(data, nodeId, nodeName, nodeType);
    }
}
