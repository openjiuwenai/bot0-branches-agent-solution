/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

/**
 * Sandbox slot (Python {@code SandboxCodeRunner}). Fails until host sets
 * {@link PythonCodeRunners#setSandboxExecutor}.
 *
 * @since 2026-08-25
 */

public final class SandboxPythonCodeExecutor implements PythonCodeExecutor {

    /**
     * execute.
     *
     * @param request request
     * @return result
     * @since 0.1.0
     */

    @Override
    public PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException {
        throw new NodeExecutionException(
                request.nodeId(),
                "jiuwen.code",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "sandbox executor not configured (Python: SECURITY_SANDBOX_SERVER / SandboxCodeRunner); "
                        + "call PythonCodeRunners.setSandboxExecutor(...)");
    }
}
