/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;

import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static final Logger LOG = Logger.getLogger(PythonCodeRunners.class.getName());
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
            if (isSandboxStrict()) {
                throw new IllegalStateException(
                        "exec_env=sandbox but no sandbox executor configured "
                                + "(set studio.dsl.sandbox.strict=false to allow local fallback)");
            }
            LOG.log(
                    Level.WARNING,
                    "exec_env=sandbox but no sandbox executor configured; falling back to local execution");
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
     * When true, sandbox misconfiguration or non-timeout failures do not fall back to local execution.
     * Controlled by {@code STUDIO_DSL_SANDBOX_STRICT} or {@code studio.dsl.sandbox.strict}.
     *
     * @return strict mode enabled
     */
    public static boolean isSandboxStrict() {
        String env = System.getenv("STUDIO_DSL_SANDBOX_STRICT");
        if (env == null || env.isBlank()) {
            env = System.getProperty("studio.dsl.sandbox.strict");
        }
        if (env == null || env.isBlank()) {
            return false;
        }
        String t = env.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t);
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
