package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.studio.dsl.model.NodeCauseCode;

/** Distinguishable node failure surface (FEAT-031 MUST). */
public class NodeExecutionException extends RuntimeException {
    private final String nodeId;
    private final String nodeType;
    private final NodeCauseCode causeCode;

    public NodeExecutionException(String nodeId, String nodeType, NodeCauseCode causeCode, String reason) {
        this(nodeId, nodeType, causeCode, reason, null);
    }

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

    public String nodeId() {
        return nodeId;
    }

    public String nodeType() {
        return nodeType;
    }

    public NodeCauseCode causeCode() {
        return causeCode;
    }
}
