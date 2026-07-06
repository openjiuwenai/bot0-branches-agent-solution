/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.rail;

/**
 * Result of {@link SandboxOps#executeCode}. Library-tier value object — decouples
 * {@link SandboxRail} from any concrete sandbox client's result type.
 *
 * <p>Contract: {@code stdout}, {@code stderr}, {@code message} are never null
 * (normalised to empty string by the compact constructor). {@code exitCode = -1}
 * indicates an unknown / transport-level failure.
 *
 * @param ok whether the execution succeeded (exit code 0 and no transport error)
 * @param exitCode process exit code, {@code -1} if unknown
 * @param stdout captured standard output (never {@code null})
 * @param stderr captured standard error (never {@code null})
 * @param message diagnostic message on failure, empty otherwise (never {@code null})
 * @since 2026-07-06
 */
public record ExecResult(boolean ok, int exitCode, String stdout, String stderr, String message) {
    /** Normalises {@code null} string fields to empty strings. */
    public ExecResult {
        if (stdout == null) {
            stdout = "";
        }
        if (stderr == null) {
            stderr = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
