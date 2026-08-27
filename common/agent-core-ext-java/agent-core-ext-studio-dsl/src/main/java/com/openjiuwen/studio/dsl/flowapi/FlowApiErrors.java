/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.Map;

/**
 * Python {@code _build_flow_api_error}.
 *
 * @since 2026-08-26
 */

public final class FlowApiErrors {
    private FlowApiErrors() {}

    /**
     * of.
     *
     * @param nodeId nodeId
     * @param status status
     * @param msg msg
     * @return result
     * @since 0.1.0
     */

    public static NodeExecutionException of(String nodeId, FlowApiStatusCode status, String msg) {
        String message = status.template().replace("{msg}", msg == null ? "" : msg);
        NodeCauseCode cause =
                status == FlowApiStatusCode.WORKFLOW_API_INIT_ERROR
                        ? NodeCauseCode.NODE_CONFIG_INVALID
                        : NodeCauseCode.NODE_INVOKE_FAILED;
        return new NodeExecutionException(
                nodeId, "jiuwen.plugin", cause, message + " [pythonErrorCode=" + status.code() + "]");
    }

    /**
     * ofCode.
     *
     * @param nodeId nodeId
     * @param errorCode errorCode
     * @param message message
     * @return result
     * @since 0.1.0
     */

    public static NodeExecutionException ofCode(String nodeId, int errorCode, String message) {
        return new NodeExecutionException(
                nodeId,
                "jiuwen.plugin",
                NodeCauseCode.NODE_INVOKE_FAILED,
                message + " [pythonErrorCode=" + errorCode + "]");
    }

    /**
     * of.
     *
     * @param nodeId nodeId
     * @param status status
     * @param msg msg
     * @param unused unused
     * @return result
     * @since 0.1.0
     */

    public static NodeExecutionException of(
            String nodeId, FlowApiStatusCode status, String msg, Map<String, String> unused) {
        return of(nodeId, status, msg);
    }
}
