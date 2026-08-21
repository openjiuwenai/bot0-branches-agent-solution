package com.openjiuwen.studio.dsl.config;

import java.util.ArrayList;
import java.util.List;

/** Configuration mirror of L2 §6 (plain POJO; Spring optional). */
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

    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    public String getPythonExecutor() {
        return pythonExecutor;
    }

    public void setPythonExecutor(String pythonExecutor) {
        this.pythonExecutor = pythonExecutor;
    }

    public String getPythonInterpreter() {
        return pythonInterpreter;
    }

    public void setPythonInterpreter(String pythonInterpreter) {
        this.pythonInterpreter = pythonInterpreter;
    }

    public long getPythonDefaultTimeoutMs() {
        return pythonDefaultTimeoutMs;
    }

    public void setPythonDefaultTimeoutMs(long pythonDefaultTimeoutMs) {
        this.pythonDefaultTimeoutMs = pythonDefaultTimeoutMs;
    }

    public String getPythonWorkdirRoot() {
        return pythonWorkdirRoot;
    }

    public void setPythonWorkdirRoot(String pythonWorkdirRoot) {
        this.pythonWorkdirRoot = pythonWorkdirRoot;
    }

    public boolean isPythonInheritEnv() {
        return pythonInheritEnv;
    }

    public void setPythonInheritEnv(boolean pythonInheritEnv) {
        this.pythonInheritEnv = pythonInheritEnv;
    }

    public List<String> getPythonEnvWhitelist() {
        return pythonEnvWhitelist;
    }

    public void setPythonEnvWhitelist(List<String> pythonEnvWhitelist) {
        this.pythonEnvWhitelist =
                pythonEnvWhitelist == null ? new ArrayList<>() : new ArrayList<>(pythonEnvWhitelist);
    }

    public boolean isJavaSpiEnabled() {
        return javaSpiEnabled;
    }

    public void setJavaSpiEnabled(boolean javaSpiEnabled) {
        this.javaSpiEnabled = javaSpiEnabled;
    }

    public String getUnsupportedModalityPolicy() {
        return unsupportedModalityPolicy;
    }

    public void setUnsupportedModalityPolicy(String unsupportedModalityPolicy) {
        this.unsupportedModalityPolicy = unsupportedModalityPolicy;
    }

    public String getVariablesScope() {
        return variablesScope;
    }

    public void setVariablesScope(String variablesScope) {
        this.variablesScope = variablesScope;
    }
}
