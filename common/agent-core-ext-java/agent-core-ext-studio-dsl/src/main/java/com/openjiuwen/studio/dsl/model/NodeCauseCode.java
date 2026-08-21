package com.openjiuwen.studio.dsl.model;

/** FEAT-031 / L2 §7.3 distinguishable failure codes. */
public enum NodeCauseCode {
    UNKNOWN_NODE_TYPE,
    NODE_CONFIG_INVALID,
    NODE_INVOKE_FAILED,
    PYTHON_TIMEOUT,
    PYTHON_NON_ZERO,
    PYTHON_IO,
    NESTING_DEPTH_EXCEEDED,
    SUBWORKFLOW_REF_INVALID,
    CODE_PATH_AMBIGUOUS,
    CODE_LOGIC_NOT_FOUND
}
