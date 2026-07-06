/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.rail;

/**
 * Narrow sandbox facade consumed by {@link SandboxRail}. Keeps the library tier
 * agnostic of any concrete sandbox client type — the runtime wrapper is responsible
 * for adapting whatever sandbox backend it wires up into this interface.
 */
public interface SandboxOps {

    /**
     * Execute a Python snippet in the sandbox with the given timeout (seconds).
     * Implementations MUST return a non-null result even on failure (populate the
     * {@code ok} flag / {@code message} accordingly).
     */
    ExecResult executeCode(String code, int timeoutSeconds);

    /**
     * Download a file from the sandbox to a local path. Returns the actual local
     * path on success, or {@code null} on failure. Implementations should ensure
     * parent directories exist.
     */
    String downloadFile(String remotePath, String localPath);
}
