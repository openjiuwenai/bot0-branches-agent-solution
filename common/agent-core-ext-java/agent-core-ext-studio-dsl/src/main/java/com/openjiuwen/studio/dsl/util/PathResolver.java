package com.openjiuwen.studio.dsl.util;

import java.util.Map;

public final class PathResolver {
    private PathResolver() {}

    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(p);
            } else {
                return null;
            }
        }
        return cur;
    }
}
