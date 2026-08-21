package com.openjiuwen.studio.dsl.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Minimal FEAT-027 assembly product consumed by FEAT-028 (L2 §2.3.2). */
public final class AssembledNode {
    private final String id;
    private final String irType;
    private final String canonicalType;
    private final Map<String, Object> configs;
    private final Map<String, Object> inputsSchema;
    private final Map<String, Object> outputsSchema;

    public AssembledNode(
            String id,
            String irType,
            String canonicalType,
            Map<String, Object> configs,
            Map<String, Object> inputsSchema,
            Map<String, Object> outputsSchema) {
        this.id = Objects.requireNonNull(id, "id");
        this.irType = Objects.requireNonNull(irType, "irType");
        this.canonicalType = Objects.requireNonNull(canonicalType, "canonicalType");
        this.configs = Collections.unmodifiableMap(new LinkedHashMap<>(configs == null ? Map.of() : configs));
        this.inputsSchema =
                Collections.unmodifiableMap(new LinkedHashMap<>(inputsSchema == null ? Map.of() : inputsSchema));
        this.outputsSchema =
                Collections.unmodifiableMap(new LinkedHashMap<>(outputsSchema == null ? Map.of() : outputsSchema));
    }

    public static AssembledNode of(String id, String irType, Map<String, Object> configs) {
        return new AssembledNode(id, irType, irType, configs, Map.of(), Map.of());
    }

    public String id() {
        return id;
    }

    public String irType() {
        return irType;
    }

    public String canonicalType() {
        return canonicalType;
    }

    public Map<String, Object> configs() {
        return configs;
    }

    public Map<String, Object> inputsSchema() {
        return inputsSchema;
    }

    public Map<String, Object> outputsSchema() {
        return outputsSchema;
    }

    public AssembledNode withCanonical(String canonical) {
        return new AssembledNode(id, irType, canonical, configs, inputsSchema, outputsSchema);
    }
}
