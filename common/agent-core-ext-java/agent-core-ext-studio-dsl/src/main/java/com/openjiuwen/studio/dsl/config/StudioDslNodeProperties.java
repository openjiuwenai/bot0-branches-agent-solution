/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration mirror of L2 §6 (plain POJO; Spring optional).
 *
 * @since 2026-08-17
 */
public final class StudioDslNodeProperties {
    private int maxNestingDepth = 5;
    private String pythonExecutor = "subprocess";
    private String pythonInterpreter = "python3";
    private long pythonDefaultTimeoutMs = 30_000L;
    private String pythonWorkdirRoot;
    private boolean pythonInheritEnv = false;
    private List<String> pythonEnvWhitelist = new ArrayList<>(List.of("PATH", "LANG"));
    private boolean javaSpiEnabled = true;
    private String unsupportedModalityPolicy = "passthrough";
    private String variablesScope = "workflow-execution";

    /**
     * getMaxNestingDepth.
     *
     * @return result
     */
    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * setMaxNestingDepth.
     *
     * @param maxNestingDepth maxNestingDepth
     */
    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    /**
     * getPythonExecutor.
     *
     * @return result
     */
    public String getPythonExecutor() {
        return pythonExecutor;
    }

    /**
     * setPythonExecutor.
     *
     * @param pythonExecutor pythonExecutor
     */
    public void setPythonExecutor(String pythonExecutor) {
        this.pythonExecutor = pythonExecutor;
    }

    /**
     * getPythonInterpreter.
     *
     * @return result
     */
    public String getPythonInterpreter() {
        return pythonInterpreter;
    }

    /**
     * setPythonInterpreter.
     *
     * @param pythonInterpreter pythonInterpreter
     */
    public void setPythonInterpreter(String pythonInterpreter) {
        this.pythonInterpreter = pythonInterpreter;
    }

    /**
     * getPythonDefaultTimeoutMs.
     *
     * @return result
     */
    public long getPythonDefaultTimeoutMs() {
        return pythonDefaultTimeoutMs;
    }

    /**
     * setPythonDefaultTimeoutMs.
     *
     * @param pythonDefaultTimeoutMs pythonDefaultTimeoutMs
     */
    public void setPythonDefaultTimeoutMs(long pythonDefaultTimeoutMs) {
        this.pythonDefaultTimeoutMs = pythonDefaultTimeoutMs;
    }

    /**
     * getPythonWorkdirRoot.
     *
     * @return result
     */
    public String getPythonWorkdirRoot() {
        return pythonWorkdirRoot;
    }

    /**
     * setPythonWorkdirRoot.
     *
     * @param pythonWorkdirRoot pythonWorkdirRoot
     */
    public void setPythonWorkdirRoot(String pythonWorkdirRoot) {
        this.pythonWorkdirRoot = pythonWorkdirRoot;
    }

    /**
     * isPythonInheritEnv.
     *
     * @return result
     */
    public boolean isPythonInheritEnv() {
        return pythonInheritEnv;
    }

    /**
     * setPythonInheritEnv.
     *
     * @param pythonInheritEnv pythonInheritEnv
     */
    public void setPythonInheritEnv(boolean pythonInheritEnv) {
        this.pythonInheritEnv = pythonInheritEnv;
    }

    /**
     * getPythonEnvWhitelist.
     *
     * @return result
     */
    public List<String> getPythonEnvWhitelist() {
        return pythonEnvWhitelist;
    }

    /**
     * setPythonEnvWhitelist.
     *
     * @param pythonEnvWhitelist pythonEnvWhitelist
     */
    public void setPythonEnvWhitelist(List<String> pythonEnvWhitelist) {
        this.pythonEnvWhitelist =
                pythonEnvWhitelist == null ? new ArrayList<>() : new ArrayList<>(pythonEnvWhitelist);
    }

    /**
     * isJavaSpiEnabled.
     *
     * @return result
     */
    public boolean isJavaSpiEnabled() {
        return javaSpiEnabled;
    }

    /**
     * setJavaSpiEnabled.
     *
     * @param javaSpiEnabled javaSpiEnabled
     */
    public void setJavaSpiEnabled(boolean javaSpiEnabled) {
        this.javaSpiEnabled = javaSpiEnabled;
    }

    /**
     * getUnsupportedModalityPolicy.
     *
     * @return result
     */
    public String getUnsupportedModalityPolicy() {
        return unsupportedModalityPolicy;
    }

    /**
     * setUnsupportedModalityPolicy.
     *
     * @param unsupportedModalityPolicy unsupportedModalityPolicy
     */
    public void setUnsupportedModalityPolicy(String unsupportedModalityPolicy) {
        this.unsupportedModalityPolicy = unsupportedModalityPolicy;
    }

    /**
     * getVariablesScope.
     *
     * @return result
     */
    public String getVariablesScope() {
        return variablesScope;
    }

    /**
     * setVariablesScope.
     *
     * @param variablesScope variablesScope
     */
    public void setVariablesScope(String variablesScope) {
        this.variablesScope = variablesScope;
    }
}
