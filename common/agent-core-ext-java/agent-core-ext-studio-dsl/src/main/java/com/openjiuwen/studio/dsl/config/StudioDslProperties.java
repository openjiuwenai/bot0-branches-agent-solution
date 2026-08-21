package com.openjiuwen.studio.dsl.config;

/**
 * Spring-facing nested properties for {@code studio-dsl.*} (L2 §6.1).
 * Converts to runtime {@link StudioDslNodeProperties}.
 */
public final class StudioDslProperties {
    private final NestedWorkflow nestedWorkflow = new NestedWorkflow();
    private final Python python = new Python();
    private final Code code = new Code();
    private final Media media = new Media();
    private final Variables variables = new Variables();

    public NestedWorkflow getNestedWorkflow() {
        return nestedWorkflow;
    }

    public Python getPython() {
        return python;
    }

    public Code getCode() {
        return code;
    }

    public Media getMedia() {
        return media;
    }

    public Variables getVariables() {
        return variables;
    }

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

    public static final class NestedWorkflow {
        private int maxDepth = 5;

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    public static final class Python {
        private String executor = "subprocess";
        private String interpreter = "python3";
        private long defaultTimeoutMs = 30_000L;
        private String workdirRoot;
        private boolean inheritEnv = false;
        private java.util.List<String> envWhitelist = new java.util.ArrayList<>(java.util.List.of("PATH", "LANG"));

        public String getExecutor() {
            return executor;
        }

        public void setExecutor(String executor) {
            this.executor = executor;
        }

        public String getInterpreter() {
            return interpreter;
        }

        public void setInterpreter(String interpreter) {
            this.interpreter = interpreter;
        }

        public long getDefaultTimeoutMs() {
            return defaultTimeoutMs;
        }

        public void setDefaultTimeoutMs(long defaultTimeoutMs) {
            this.defaultTimeoutMs = defaultTimeoutMs;
        }

        public String getWorkdirRoot() {
            return workdirRoot;
        }

        public void setWorkdirRoot(String workdirRoot) {
            this.workdirRoot = workdirRoot;
        }

        public boolean isInheritEnv() {
            return inheritEnv;
        }

        public void setInheritEnv(boolean inheritEnv) {
            this.inheritEnv = inheritEnv;
        }

        public java.util.List<String> getEnvWhitelist() {
            return envWhitelist;
        }

        public void setEnvWhitelist(java.util.List<String> envWhitelist) {
            this.envWhitelist = envWhitelist == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(envWhitelist);
        }
    }

    public static final class Code {
        private boolean javaSpiEnabled = true;

        public boolean isJavaSpiEnabled() {
            return javaSpiEnabled;
        }

        public void setJavaSpiEnabled(boolean javaSpiEnabled) {
            this.javaSpiEnabled = javaSpiEnabled;
        }
    }

    public static final class Media {
        private String unsupportedModalityPolicy = "passthrough";

        public String getUnsupportedModalityPolicy() {
            return unsupportedModalityPolicy;
        }

        public void setUnsupportedModalityPolicy(String unsupportedModalityPolicy) {
            this.unsupportedModalityPolicy = unsupportedModalityPolicy;
        }
    }

    public static final class Variables {
        private String scope = "workflow-execution";

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
