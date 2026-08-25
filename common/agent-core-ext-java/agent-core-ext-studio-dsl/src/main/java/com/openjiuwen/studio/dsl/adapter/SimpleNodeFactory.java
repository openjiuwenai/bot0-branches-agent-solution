/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.Set;

/**
 * SimpleNodeFactory for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class SimpleNodeFactory implements NodeHandlerFactory {
    private final String canonical;
    private final Set<String> aliases;

    /**
     * SimpleNodeFactory.
     *
     * @param canonical canonical
     * @param aliases aliases
     */
    public SimpleNodeFactory(String canonical, String... aliases) {
        this.canonical = canonical;
        this.aliases = aliases == null ? Set.of() : Set.of(aliases);
    }

    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return canonical;
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return aliases;
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new PassthroughStudioNode(node);
    }
}
