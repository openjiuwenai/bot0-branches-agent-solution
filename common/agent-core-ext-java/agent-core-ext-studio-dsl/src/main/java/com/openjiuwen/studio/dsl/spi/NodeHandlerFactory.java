/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;

import java.util.Set;

/**
 * Unified node SPI (FEAT-031 MUST).
 *
 * @since 2026-08-17
 */
public interface NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     */
    String canonicalType();

    /**
     * aliases.
     *
     * @return result
     */
    default Set<String> aliases() {
        return Set.of();
    }

    /**
     * supports.
     *
     * @param irType irType
     * @return result
     */
    default boolean supports(String irType) {
        if (irType == null) {
            return false;
        }
        return canonicalType().equals(irType) || aliases().contains(irType);
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     * @throws NodeExecutionException when the call fails
     */
    ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) throws NodeExecutionException;
}
