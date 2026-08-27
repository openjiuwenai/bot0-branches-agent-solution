/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal assembly product consumed by FEAT-031 (L2 §2.4); produced by DSL assembler / host.
 *
 * @since 2026-08-17
 */

public final class AssembledNode {
    private final String id;
    private final String irType;
    private final String canonicalType;
    private final Map<String, Object> configs;
    private final Map<String, Object> inputsSchema;
    private final Map<String, Object> outputsSchema;

    /**
     * AssembledNode.
     *
     * @param id id
     * @param irType irType
     * @param canonicalType canonicalType
     * @param configs configs
     * @param inputsSchema inputsSchema
     * @param outputsSchema outputsSchema
     */

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

    /**
     * of.
     *
     * @param id id
     * @param irType irType
     * @param configs configs
     * @return result
     */

    public static AssembledNode of(String id, String irType, Map<String, Object> configs) {
        return new AssembledNode(id, irType, irType, configs, Map.of(), Map.of());
    }

    /**
     * id.
     *
     * @return result
     */

    public String id() {
        return id;
    }

    /**
     * irType.
     *
     * @return result
     */

    public String irType() {
        return irType;
    }

    /**
     * canonicalType.
     *
     * @return result
     */

    public String canonicalType() {
        return canonicalType;
    }

    /**
     * configs.
     *
     * @return result
     */

    public Map<String, Object> configs() {
        return configs;
    }

    /**
     * inputsSchema.
     *
     * @return result
     */

    public Map<String, Object> inputsSchema() {
        return inputsSchema;
    }

    /**
     * outputsSchema.
     *
     * @return result
     */

    public Map<String, Object> outputsSchema() {
        return outputsSchema;
    }

    /**
     * withCanonical.
     *
     * @param canonical canonical
     * @return result
     */

    public AssembledNode withCanonical(String canonical) {
        return new AssembledNode(id, irType, canonical, configs, inputsSchema, outputsSchema);
    }
}
