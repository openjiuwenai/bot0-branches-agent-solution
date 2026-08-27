/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;

/**
 * connectorType → adapter (Python {@code KBAdapterFactory}).
 *
 * @since 2026-08-25
 */

public final class KBAdapterFactory {
    private static final Map<String, Supplier<KBServiceAdapter>> REGISTRY = new ConcurrentHashMap<>();

    static {
        register("LakeSearch", LakeSearchAdapter::new);
        register("LakeSearchInside", LakeSearchAdapter::new);
        register("KooSearch", KooSearchAdapter::new);
        register("KooSearchInside", KooSearchAdapter::new);
        register("Ragflow", RagFlowAdapter::new);
        register("General", GeneralKBAdapter::new);
        register("Custom", LakeSearchAdapter::new);
        register("OpenJiuwen", OpenJiuwenKBAdapter::new);
    }

    private KBAdapterFactory() {}

    /**
     * register.
     *
     * @param connectorType connectorType
     * @param supplier supplier
     */

    public static void register(String connectorType, Supplier<KBServiceAdapter> supplier) {
        REGISTRY.put(connectorType, supplier);
    }

    /**
     * create.
     *
     * @param connectorType connectorType
     * @return adapter
     */

    public static KBServiceAdapter create(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
        throw new IllegalArgumentException("Unsupported connector type: (empty)");
    }
        Supplier<KBServiceAdapter> s = REGISTRY.get(connectorType);
        if (s != null) {
            return s.get();
        }
        String lower = connectorType.toLowerCase();
        for (Map.Entry<String, Supplier<KBServiceAdapter>> e : REGISTRY.entrySet()) {
            if (e.getKey().toLowerCase().equals(lower)) {
                return e.getValue().get();
            }
        }
        throw new IllegalArgumentException(
                "Unsupported connector type: " + connectorType + ". Available: " + supportedTypes());
    }

    /**
     * supportedTypes.
     *
     * @return types
     */

    public static List<String> supportedTypes() {
        return new ArrayList<>(REGISTRY.keySet());
    }
}
