/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.config;

/**
 * Spring-facing nested properties for {@code studio-dsl.*} (L2 §6.1).
 * Converts to runtime {@link StudioDslNodeProperties}.
 *
 * @since 2026-08-17
 */
public final class StudioDslProperties {
    private final NestedWorkflow nestedWorkflow = new NestedWorkflow();
    private final Python python = new Python();
    private final Code code = new Code();
    private final Media media = new Media();
    private final Variables variables = new Variables();
    /**
     * getNestedWorkflow.
     */
    public NestedWorkflow getNestedWorkflow() {
        return nestedWorkflow;
    }
    /**
     * getPython.
     */
    public Python getPython() {
        return python;
    }
    /**
     * getCode.
     */
    public Code getCode() {
        return code;
    }
    /**
     * getMedia.
     */
    public Media getMedia() {
        return media;
    }
    /**
     * getVariables.
     */
    public Variables getVariables() {
        return variables;
    }
    /**
     * toNodeProperties.
     */
    public StudioDslNodeProperties toNodeProperties() {
        StudioDslNodeProperties p = new StudioDslNodeProperties();
        p.setMaxNestingDepth(nestedWorkflow.getMaxDepth());
        p.setPythonExecutor(python.getExecutor());
        p.setPythonInterpreter(python.getInterpreter());
        p.setPythonDefaultTimeoutMs(python.getDefaultTimeoutMs());
        p.setPythonWorkdirRoot(python.getWorkdirRoot());
        p.setPythonInheritEnv(python.isInheritEnv());
        p.setPythonEnvWhitelist(python.getEnvWhitelist());
        p.setJavaSpiEnabled(code.isJavaSpiEnabled());
        p.setUnsupportedModalityPolicy(media.getUnsupportedModalityPolicy());
        p.setVariablesScope(variables.getScope());
        return p;
    }

    /**
     * Nested workflow limits.
     */
    public static final class NestedWorkflow {
        private int maxDepth = 5;
        /**
         * getMaxDepth.
         */
        public int getMaxDepth() {
            return maxDepth;
        }
        /**
         * setMaxDepth.
         * @param maxDepth maxDepth
         */
        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    /**
     * Python executor settings.
     */
    public static final class Python {
        private String executor = "subprocess";
        private String interpreter = "python3";
        private long defaultTimeoutMs = 30_000L;
        private String workdirRoot;
        private boolean inheritEnv = false;
        private java.util.List<String> envWhitelist = new java.util.ArrayList<>(java.util.List.of("PATH", "LANG"));
        /**
         * getExecutor.
         */
        public String getExecutor() {
            return executor;
        }
        /**
         * setExecutor.
         * @param executor executor
         */
        public void setExecutor(String executor) {
            this.executor = executor;
        }
        /**
         * getInterpreter.
         */
        public String getInterpreter() {
            return interpreter;
        }
        /**
         * setInterpreter.
         * @param interpreter interpreter
         */
        public void setInterpreter(String interpreter) {
            this.interpreter = interpreter;
        }
        /**
         * getDefaultTimeoutMs.
         */
        public long getDefaultTimeoutMs() {
            return defaultTimeoutMs;
        }
        /**
         * setDefaultTimeoutMs.
         * @param defaultTimeoutMs defaultTimeoutMs
         */
        public void setDefaultTimeoutMs(long defaultTimeoutMs) {
            this.defaultTimeoutMs = defaultTimeoutMs;
        }
        /**
         * getWorkdirRoot.
         */
        public String getWorkdirRoot() {
            return workdirRoot;
        }
        /**
         * setWorkdirRoot.
         * @param workdirRoot workdirRoot
         */
        public void setWorkdirRoot(String workdirRoot) {
            this.workdirRoot = workdirRoot;
        }
        /**
         * isInheritEnv.
         */
        public boolean isInheritEnv() {
            return inheritEnv;
        }
        /**
         * setInheritEnv.
         * @param inheritEnv inheritEnv
         */
        public void setInheritEnv(boolean inheritEnv) {
            this.inheritEnv = inheritEnv;
        }
        /**
         * getEnvWhitelist.
         */
        public java.util.List<String> getEnvWhitelist() {
            return envWhitelist;
        }
        /**
         * setEnvWhitelist.
         * @param envWhitelist envWhitelist
         */
        public void setEnvWhitelist(java.util.List<String> envWhitelist) {
            this.envWhitelist = envWhitelist == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(envWhitelist);
        }
    }

    /**
     * Java code SPI settings.
     */
    public static final class Code {
        private boolean javaSpiEnabled = true;
        /**
         * isJavaSpiEnabled.
         */
        public boolean isJavaSpiEnabled() {
            return javaSpiEnabled;
        }
        /**
         * setJavaSpiEnabled.
         * @param javaSpiEnabled javaSpiEnabled
         */
        public void setJavaSpiEnabled(boolean javaSpiEnabled) {
            this.javaSpiEnabled = javaSpiEnabled;
        }
    }

    /**
     * Media modality settings.
     */
    public static final class Media {
        private String unsupportedModalityPolicy = "passthrough";
        /**
         * getUnsupportedModalityPolicy.
         */
        public String getUnsupportedModalityPolicy() {
            return unsupportedModalityPolicy;
        }
        /**
         * setUnsupportedModalityPolicy.
         * @param unsupportedModalityPolicy unsupportedModalityPolicy
         */
        public void setUnsupportedModalityPolicy(String unsupportedModalityPolicy) {
            this.unsupportedModalityPolicy = unsupportedModalityPolicy;
        }
    }

    /**
     * Variable scope settings.
     */
    public static final class Variables {
        private String scope = "workflow-execution";
        /**
         * getScope.
         */
        public String getScope() {
            return scope;
        }
        /**
         * setScope.
         * @param scope scope
         */
        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
