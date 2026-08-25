/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.studio.dsl.model.NodeCauseCode;

/**
 * Distinguishable node failure surface (FEAT-031 MUST).
 *
 * @since 2026-08-17
 */
public class NodeExecutionException extends RuntimeException {
    private final String nodeId;
    private final String nodeType;
    private final NodeCauseCode causeCode;

    /**
     * NodeExecutionException.
     *
     * @param nodeId nodeId
     * @param nodeType nodeType
     * @param causeCode causeCode
     * @param reason reason
     */
    public NodeExecutionException(String nodeId, String nodeType, NodeCauseCode causeCode, String reason) {
        this(nodeId, nodeType, causeCode, reason, null);
    }

    /**
     * NodeExecutionException.
     *
     * @param nodeId nodeId
     * @param nodeType nodeType
     * @param causeCode causeCode
     * @param reason reason
     * @param cause cause
     */
    public NodeExecutionException(
            String nodeId, String nodeType, NodeCauseCode causeCode, String reason, Throwable cause) {
        super(format(nodeId, nodeType, causeCode, reason), cause);
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.causeCode = causeCode;
    }

    private static String format(String nodeId, String nodeType, NodeCauseCode code, String reason) {
        return "nodeId=" + nodeId + ", nodeType=" + nodeType + ", causeCode=" + code + ", reason=" + reason;
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
     * nodeType.
     *
     * @return result
     */
    public String nodeType() {
        return nodeType;
    }

    /**
     * causeCode.
     *
     * @return result
     */
    public NodeCauseCode causeCode() {
        return causeCode;
    }
}
