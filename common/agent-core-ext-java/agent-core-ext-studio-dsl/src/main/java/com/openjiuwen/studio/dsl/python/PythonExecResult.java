/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import java.util.Map;

/**
 * PythonExecResult for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class PythonExecResult {
    private final Map<String, Object> outputs;
    private final String stdout;
    private final String stderr;
    private final int exitCode;

    /**
     * PythonExecResult.
     *
     * @param outputs outputs
     * @param stdout stdout
     * @param stderr stderr
     * @param exitCode exitCode
     */
    public PythonExecResult(Map<String, Object> outputs, String stdout, String stderr, int exitCode) {
        this.outputs = outputs;
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
    }

    /**
     * outputs.
     *
     * @return result
     */
    public Map<String, Object> outputs() {
        return outputs;
    }

    /**
     * stdout.
     *
     * @return result
     */
    public String stdout() {
        return stdout;
    }

    /**
     * stderr.
     *
     * @return result
     */
    public String stderr() {
        return stderr;
    }

    /**
     * exitCode.
     *
     * @return result
     */
    public int exitCode() {
        return exitCode;
    }
}
