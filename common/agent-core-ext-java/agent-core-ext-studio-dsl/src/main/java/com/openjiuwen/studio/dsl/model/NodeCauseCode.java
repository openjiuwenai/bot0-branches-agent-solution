/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.model;

/**
 * FEAT-031 / L2 §7.3 distinguishable failure codes.
 *
 * @since 2026-08-17
 */

public enum NodeCauseCode {

    /**
     * Unknown canonical node type.
     */
    UNKNOWN_NODE_TYPE,

    /**
     * Node config is invalid.
     */
    NODE_CONFIG_INVALID,

    /**
     * Node invoke failed.
     */
    NODE_INVOKE_FAILED,

    /**
     * Python subprocess timed out.
     */
    PYTHON_TIMEOUT,

    /**
     * Python subprocess non-zero exit.
     */
    PYTHON_NON_ZERO,

    /**
     * Python IO / parse failure.
     */
    PYTHON_IO,

    /**
     * Nested workflow depth exceeded.
     */
    NESTING_DEPTH_EXCEEDED,

    /**
     * Sub-workflow reference is invalid.
     */
    SUBWORKFLOW_REF_INVALID,

    /**
     * Code path is ambiguous.
     */
    CODE_PATH_AMBIGUOUS
}
