/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import com.openjiuwen.studio.dsl.model.AssembledWorkflow;

import java.util.Map;

/**
 * Resolves nested workflow refs (provided by FEAT-027 impl or tests).
 *
 * @since 2026-08-17
 */
@FunctionalInterface
public interface SubWorkflowResolver {
    /**
     * resolve.
     *
     * @param configs configs
     * @return result
     */
    AssembledWorkflow resolve(Map<String, Object> configs);
}
