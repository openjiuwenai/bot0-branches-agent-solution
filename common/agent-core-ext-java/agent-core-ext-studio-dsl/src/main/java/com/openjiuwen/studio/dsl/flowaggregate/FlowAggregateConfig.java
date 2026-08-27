/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowaggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code AggregateConfig} / {@code GroupConfig} from {@code flow_aggregate.py}.
 *
 * @since 2026-08-26
 */

public final class FlowAggregateConfig {

    /**
     * DEFAULT_MODE.
     *
     * @since 0.1.0
     */

    public static final String DEFAULT_MODE = "first-non-null";

    private final String mode;
    private final Map<String, List<String>> groups;
    private final Map<String, Object> raw;

    /**
     * FlowAggregateConfig.
     *
     * @param mode mode
     * @param groups groups
     * @param raw raw
     * @since 0.1.0
     */

    public FlowAggregateConfig(String mode, Map<String, List<String>> groups, Map<String, Object> raw) {
        this.mode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode;
        this.groups = groups == null ? Map.of() : Map.copyOf(copyGroups(groups));
        this.raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /**
     * fromNodeConfigs.
     *
     * @param configs configs
     * @return result
     * @since 0.1.0
     */

    public static FlowAggregateConfig fromNodeConfigs(Map<String, Object> configs) {
        if (configs == null || configs.isEmpty()) {
        throw new IllegalArgumentException("conf is required");
    }
        String mode = String.valueOf(configs.getOrDefault("mode", DEFAULT_MODE));
        return new FlowAggregateConfig(mode, groupsMap(configs.get("groups")), new LinkedHashMap<>(configs));
    }

    /**
     * * Python {@code AggregateConfig.groups_map}.
     *
     * @param raw raw
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> groupsMap(Object raw) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                List<String> keys = new ArrayList<>();
                if (v instanceof List<?> list) {
                    for (Object x : list) {
                        keys.add(String.valueOf(x));
                    }
                } else if (v instanceof Object[] arr) {
                    for (Object x : arr) {
                        keys.add(String.valueOf(x));
                    }
                } else if (v != null) {
                    keys.add(String.valueOf(v));
                }
                normalized.put(String.valueOf(k), keys);
            });
            return normalized;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> g)) {
                    continue;
                }
                Object idObj = g.get("id");
                if (idObj == null) {
                    continue;
                }
                Object vl = g.get("value_list");
                if (vl == null) {
                    vl = g.get("valueList");
                }
                List<String> keys = new ArrayList<>();
                if (vl instanceof List<?> vs) {
                    for (Object x : vs) {
                        keys.add(String.valueOf(x));
                    }
                }
                normalized.put(String.valueOf(idObj), keys);
            }
        }
        return normalized;
    }

    private static Map<String, List<String>> copyGroups(Map<String, List<String>> groups) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        groups.forEach((k, v) -> out.put(k, List.copyOf(v == null ? List.of() : v)));
        return out;
    }

    /**
     * mode.
     *
     * @return result
     * @since 0.1.0
     */

    public String mode() {
        return mode;
    }
    public Map<String, List<String>> groups() {
        return groups;
    }

    public Map<String, Object> raw() {
        return raw;
    }
}
