package com.openjiuwen.studio.dsl.model;

/** FEAT-028 / L2 §4.6 distinguishable failure codes. */
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
