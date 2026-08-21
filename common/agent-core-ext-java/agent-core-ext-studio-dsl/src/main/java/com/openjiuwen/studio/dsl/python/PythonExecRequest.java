package com.openjiuwen.studio.dsl.python;

import java.util.List;
import java.util.Map;

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

    public PythonExecRequest(String nodeId, String script, Map<String, Object> inputs, long timeoutMs, String interpreter) {
        this(nodeId, script, inputs, timeoutMs, interpreter, null, null, null, false, List.of("PATH", "LANG"));
    }

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

    public String nodeId() {
        return nodeId;
    }

    public String script() {
        return script;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public String interpreter() {
        return interpreter;
    }

    public String tenantId() {
        return tenantId;
    }

    public String workflowExecutionId() {
        return workflowExecutionId;
    }

    public String workdirRoot() {
        return workdirRoot;
    }

    public boolean inheritEnv() {
        return inheritEnv;
    }

    public List<String> envWhitelist() {
        return envWhitelist;
    }
}
