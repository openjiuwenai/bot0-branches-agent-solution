/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import java.util.List;
import java.util.Map;

/**
 * PythonExecRequest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class PythonExecRequest {
    private final String nodeId;
    private final String script;
    private final Map<String, Object> inputs;
    private final long timeoutMs;
    private final String interpreter;
    private final String tenantId;
    private final String workflowExecutionId;
    private final String workdirRoot;
    private final boolean inheritEnv;
    private final List<String> envWhitelist;
    /**
     * PythonExecRequest.
     * @param nodeId nodeId
     * @param script script
     * @param inputs inputs
     * @param timeoutMs timeoutMs
     * @param interpreter interpreter
     */
    public PythonExecRequest(
            String nodeId,
            String script,
            Map<String, Object> inputs,
            long timeoutMs,
            String interpreter) {
        this(nodeId, script, inputs, timeoutMs, interpreter, null, null, null, false, List.of("PATH", "LANG"));
    }
    /**
     * PythonExecRequest.
     * @param nodeId nodeId
     * @param script script
     * @param inputs inputs
     * @param timeoutMs timeoutMs
     * @param interpreter interpreter
     * @param tenantId tenantId
     * @param workflowExecutionId workflowExecutionId
     * @param workdirRoot workdirRoot
     * @param inheritEnv inheritEnv
     * @param envWhitelist envWhitelist
     */
    public PythonExecRequest(
            String nodeId,
            String script,
            Map<String, Object> inputs,
            long timeoutMs,
            String interpreter,
            String tenantId,
            String workflowExecutionId,
            String workdirRoot,
            boolean inheritEnv,
            List<String> envWhitelist) {
        this.nodeId = nodeId;
        this.script = script;
        this.inputs = inputs;
        this.timeoutMs = timeoutMs;
        this.interpreter = interpreter == null || interpreter.isBlank() ? "python3" : interpreter;
        this.tenantId = tenantId;
        this.workflowExecutionId = workflowExecutionId;
        this.workdirRoot = workdirRoot;
        this.inheritEnv = inheritEnv;
        this.envWhitelist = envWhitelist == null ? List.of() : List.copyOf(envWhitelist);
    }
    /**
     * nodeId.
     */
    public String nodeId() {
        return nodeId;
    }
    /**
     * script.
     */
    public String script() {
        return script;
    }
    /**
     * inputs.
     */
    public Map<String, Object> inputs() {
        return inputs;
    }
    /**
     * timeoutMs.
     */
    public long timeoutMs() {
        return timeoutMs;
    }
    /**
     * interpreter.
     */
    public String interpreter() {
        return interpreter;
    }
    /**
     * tenantId.
     */
    public String tenantId() {
        return tenantId;
    }
    /**
     * workflowExecutionId.
     */
    public String workflowExecutionId() {
        return workflowExecutionId;
    }
    /**
     * workdirRoot.
     */
    public String workdirRoot() {
        return workdirRoot;
    }
    /**
     * inheritEnv.
     */
    public boolean inheritEnv() {
        return inheritEnv;
    }
    /**
     * envWhitelist.
     */
    public List<String> envWhitelist() {
        return envWhitelist;
    }
}
