/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;

/**
 * Selects Python executors like Python {@code CodeRunnerFactory} + {@code LOCAL_CODE_EXEC_MODE}.
 *
 * <ul>
 *   <li>{@code exec_env=local} + {@code inprocess} → {@link InprocessPythonCodeExecutor}
 *   <li>{@code exec_env=local} + {@code subprocess} → {@link SubprocessPythonCodeExecutor}
 *   <li>{@code exec_env=sandbox} → configured sandbox, else fallback to local (Python SysOp missing)
 * </ul>
 *
 * @since 2026-08-25
 */
public final class PythonCodeRunners {
    private static volatile PythonCodeExecutor sandboxExecutor;

    private PythonCodeRunners() {}

    /**
     * Optional host-provided sandbox implementation (Python SandboxCodeRunner analogue).
     *
     * @param executor executor (null clears)
     */
    public static void setSandboxExecutor(PythonCodeExecutor executor) {
        sandboxExecutor = executor;
    }

    /**
     * resolve.
     *
     * @param execEnv exec_env from node configs (local|sandbox); unknown → local
     * @param localExecMode inprocess|subprocess
     * @param fallbackSubprocess usually ctx.pythonExecutor()
     * @return result
     */
    public static PythonCodeExecutor resolve(
            String execEnv, String localExecMode, PythonCodeExecutor fallbackSubprocess) {
        String env = normalizeExecEnv(execEnv);
        if ("sandbox".equals(env)) {
            PythonCodeExecutor sb = sandboxExecutor;
            if (sb != null) {
                return sb;
            }
            // Python: SandboxRunner.create fails → _ensure_code_runner falls back to local
            return resolveLocal(localExecMode, fallbackSubprocess);
        }
        return resolveLocal(localExecMode, fallbackSubprocess);
    }

    /**
     * resolveLocal.
     *
     * @param localExecMode mode
     * @param fallbackSubprocess fallback
     * @return result
     */
    public static PythonCodeExecutor resolveLocal(
            String localExecMode, PythonCodeExecutor fallbackSubprocess) {
        String mode = localExecMode == null || localExecMode.isBlank()
                ? defaultLocalExecMode()
                : localExecMode.trim().toLowerCase();
        if ("inprocess".equals(mode)) {
            return new InprocessPythonCodeExecutor();
        }
        return fallbackSubprocess != null ? fallbackSubprocess : new SubprocessPythonCodeExecutor();
    }

    /**
     * Python settings.code_execution.local_exec_mode default is inprocess.
     *
     * @return result
     */
    public static String defaultLocalExecMode() {
        String env = System.getenv("LOCAL_CODE_EXEC_MODE");
        if (env == null || env.isBlank()) {
            env = System.getProperty("studio.dsl.code.local-exec-mode");
        }
        if (env == null || env.isBlank()) {
            return "inprocess";
        }
        return env.trim().toLowerCase();
    }

    /**
     * normalizeExecEnv.
     *
     * @param execEnv execEnv
     * @return local|sandbox
     */
    public static String normalizeExecEnv(String execEnv) {
        if (execEnv == null || execEnv.isBlank()) {
            return "local";
        }
        String env = execEnv.trim().toLowerCase();
        if ("local".equals(env) || "sandbox".equals(env)) {
            return env;
        }
        // Python: unsupported exec_env falls back to local
        return "local";
    }
}
