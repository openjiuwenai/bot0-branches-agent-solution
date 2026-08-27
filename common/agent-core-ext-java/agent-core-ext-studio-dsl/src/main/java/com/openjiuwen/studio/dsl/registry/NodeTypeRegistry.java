/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * alias → canonical → factory. Built-ins only via {@link BuiltinNodeBootstrap};
 * no ServiceLoader / replace override path.
 *
 * @since 2026-08-17
 */

public final class NodeTypeRegistry {
    private final Map<String, NodeHandlerFactory> byCanonical = new LinkedHashMap<>();
    private final Map<String, String> aliasToCanonical = new LinkedHashMap<>();

    /**
     * Built-in bootstrap only ({@link BuiltinNodeBootstrap}); not a public extension API (FEAT-031 OUT).
     *
     * @param factory factory
     */

    synchronized void register(NodeHandlerFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String canonical = factory.canonicalType();
        Objects.requireNonNull(canonical, "canonicalType");
        if (byCanonical.containsKey(canonical)) {
            throw new IllegalStateException("duplicate canonicalType: " + canonical);
        }
        if (aliasToCanonical.containsKey(canonical)) {
            throw new IllegalStateException("canonical conflicts with existing alias: " + canonical);
        }
        for (String alias : factory.aliases()) {
            if (byCanonical.containsKey(alias)) {
                throw new IllegalStateException("alias conflict: " + alias);
            }
            String owner = aliasToCanonical.get(alias);
            if (owner != null && !owner.equals(canonical)) {
                throw new IllegalStateException("alias conflict: " + alias);
            }
        }
        byCanonical.put(canonical, factory);
        for (String alias : factory.aliases()) {
            aliasToCanonical.put(alias, canonical);
        }
    }

    /**
     * canonicalize.
     *
     * @param irType irType
     * @return result
     */

    public String canonicalize(String irType) {
        return canonicalize(irType, "n/a");
    }

    /**
     * canonicalize with node id for failure surface.
     *
     * @param irType irType
     * @param nodeId node id for {@link NodeExecutionException}
     * @return canonical type
     */

    public String canonicalize(String irType, String nodeId) {
        String nid = nodeId == null || nodeId.isBlank() ? "n/a" : nodeId;
        if (irType == null) {
            throw new NodeExecutionException(nid, "unknown", NodeCauseCode.UNKNOWN_NODE_TYPE, "irType is null");
        }
        if (byCanonical.containsKey(irType)) {
            return irType;
        }
        String canonical = aliasToCanonical.get(irType);
        if (canonical == null) {
            throw new NodeExecutionException(nid, irType, NodeCauseCode.UNKNOWN_NODE_TYPE, "unknown IR: " + irType);
        }
        return canonical;
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     */

    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        String canonical = canonicalize(node.irType(), node.id());
        NodeHandlerFactory factory = byCanonical.get(canonical);
        AssembledNode normalized = node.withCanonical(canonical);
        return factory.create(normalized, ctx);
    }

    /**
     * canonicalTypes.
     *
     * @return result
     */

    public Set<String> canonicalTypes() {
        return Set.copyOf(byCanonical.keySet());
    }

    /**
     * factories.
     *
     * @return result
     */

    public Collection<NodeHandlerFactory> factories() {
        return byCanonical.values();
    }

    /**
     * Built-in handlers only (no ServiceLoader).
     *
     * @return result
     */

    public static NodeTypeRegistry createWithBuiltins() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        BuiltinNodeBootstrap.registerAll(registry);
        return registry;
    }
}
