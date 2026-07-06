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
 */
public record ExecResult(boolean ok, int exitCode, String stdout, String stderr, String message) {
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
