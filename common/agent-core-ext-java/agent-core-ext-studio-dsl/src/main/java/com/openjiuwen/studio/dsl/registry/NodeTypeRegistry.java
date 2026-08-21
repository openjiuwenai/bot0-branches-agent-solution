package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/** alias → canonical → factory (L2 §4.1). */
public final class NodeTypeRegistry {
    private final Map<String, NodeHandlerFactory> byCanonical = new LinkedHashMap<>();
    private final Map<String, String> aliasToCanonical = new LinkedHashMap<>();

    public synchronized void register(NodeHandlerFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String canonical = factory.canonicalType();
        if (byCanonical.containsKey(canonical)) {
            throw new IllegalStateException("duplicate canonicalType: " + canonical);
        }
        if (aliasToCanonical.containsKey(canonical)) {
            throw new IllegalStateException("canonical conflicts with existing alias: " + canonical);
        }
        for (String alias : factory.aliases()) {
            if (byCanonical.containsKey(alias) || aliasToCanonical.containsKey(alias)) {
                throw new IllegalStateException("alias conflict: " + alias);
            }
        }
        byCanonical.put(canonical, factory);
        for (String alias : factory.aliases()) {
            aliasToCanonical.put(alias, canonical);
        }
    }

    public void loadServiceLoader() {
        for (NodeHandlerFactory factory : ServiceLoader.load(NodeHandlerFactory.class)) {
            register(factory);
        }
    }

    public String canonicalize(String irType) {
        if (irType == null) {
            throw new NodeExecutionException("n/a", "unknown", NodeCauseCode.UNKNOWN_NODE_TYPE, "irType is null");
        }
        if (byCanonical.containsKey(irType)) {
            return irType;
        }
        String canonical = aliasToCanonical.get(irType);
        if (canonical == null) {
            throw new NodeExecutionException("n/a", irType, NodeCauseCode.UNKNOWN_NODE_TYPE, "unknown IR: " + irType);
        }
        return canonical;
    }

    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        String canonical = canonicalize(node.irType());
        NodeHandlerFactory factory = byCanonical.get(canonical);
        AssembledNode normalized = node.withCanonical(canonical);
        return factory.create(normalized, ctx);
    }

    public Set<String> canonicalTypes() {
        return Set.copyOf(byCanonical.keySet());
    }

    public Collection<NodeHandlerFactory> factories() {
        return byCanonical.values();
    }

    /**
     * Builtins first, then ServiceLoader custom factories (L2 §4.1 / §4.7).
     * Custom factories must not occupy built-in canonical or alias names.
     */
    public static NodeTypeRegistry createWithBuiltins() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        BuiltinNodeBootstrap.registerAll(registry);
        registry.loadServiceLoader();
        return registry;
    }
}
