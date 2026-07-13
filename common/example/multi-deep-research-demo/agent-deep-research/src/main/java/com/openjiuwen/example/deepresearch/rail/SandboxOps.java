/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.rail;

import java.util.Optional;

/**
 * Narrow sandbox facade consumed by {@link SandboxRail}. Keeps the library tier
 * agnostic of any concrete sandbox client type — the runtime wrapper is responsible
 * for adapting whatever sandbox backend it wires up into this interface.
 *
 * @since 2026-07-06
 */
public interface SandboxOps {
    /**
     * Executes a Python snippet in the sandbox with the given timeout (seconds).
     *
     * <p>Implementations MUST return a non-null result even on failure — populate the
     * {@link ExecResult#ok()} flag / {@link ExecResult#message()} accordingly.
     *
     * @param code the Python source code to execute
     * @param timeoutSeconds the wall-clock timeout in seconds
     * @return the execution result (never {@code null})
     */
    ExecResult executeCode(String code, int timeoutSeconds);

    /**
     * Downloads a file from the sandbox to a local path.
     *
     * <p>Implementations should ensure parent directories exist.
     *
     * @param remotePath the sandbox-side file path
     * @param localPath the desired local destination path
     * @return the actual local path on success, or {@link Optional#empty()} on failure
     */
    Optional<String> downloadFile(String remotePath, String localPath);
}
