package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;

public interface PythonCodeExecutor {
    PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException;
}
