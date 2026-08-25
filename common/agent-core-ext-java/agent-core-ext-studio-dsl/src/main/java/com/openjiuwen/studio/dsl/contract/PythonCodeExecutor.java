/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;

/**
 * PythonCodeExecutor for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public interface PythonCodeExecutor {
    /**
     * execute.
     *
     * @param request request
     * @return result
     * @throws NodeExecutionException when the call fails
     */
    PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException;
}
