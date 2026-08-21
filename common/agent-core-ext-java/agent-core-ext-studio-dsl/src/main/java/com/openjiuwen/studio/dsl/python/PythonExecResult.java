package com.openjiuwen.studio.dsl.python;

import java.util.Map;

public final class PythonExecResult {
    private final Map<String, Object> outputs;
    private final String stdout;
    private final String stderr;
    private final int exitCode;

    public PythonExecResult(Map<String, Object> outputs, String stdout, String stderr, int exitCode) {
        this.outputs = outputs;
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public int exitCode() {
        return exitCode;
    }
}
