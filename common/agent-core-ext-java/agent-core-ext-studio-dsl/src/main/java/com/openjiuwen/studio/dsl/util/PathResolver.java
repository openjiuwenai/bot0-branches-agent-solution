/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.Map;
import java.util.Optional;

/**
 * Dot-path lookup over nested maps (Studio userFields).
 *
 * @since 2026-08-17
 */

public final class PathResolver {
    private PathResolver() {}

    /**
     * Resolve a dotted path against a map.
     *
     * @param root root
     * @param path path
     * @return value when present
     */

    @SuppressWarnings("unchecked")
    public static Optional<Object> get(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
        return Optional.empty();
    }
        Object cur = root;
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return Optional.empty();
            }
            cur = m.get(p);
        }
        return Optional.ofNullable(cur);
    }
}
