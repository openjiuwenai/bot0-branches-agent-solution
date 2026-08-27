/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dot/bracket path access for dict-stream transforms (Python {@code get_by_path}).
 *
 * @since 2026-08-25
 */

public final class DictStreamPath {
    private DictStreamPath() {}

    /**
     * Get a nested value from dict/list by a path like {@code a.b[0].c}.
     *
     * @param obj root object
     * @param path path
     * @param defaultValue default when missing
     * @return resolved value or default
     */

    @SuppressWarnings("unchecked")
    public static Object getByPath(Object obj, String path, Object defaultValue) {
        List<Object> tokens = parsePath(path);
        Object cur = obj;
        for (Object tok : tokens) {
            if (tok instanceof Integer idx) {
                if (!(cur instanceof List<?> list) || idx < 0 || idx >= list.size()) {
                    return defaultValue;
                }
                cur = list.get(idx);
            } else {
                String key = String.valueOf(tok);
                if (!(cur instanceof Map<?, ?> map) || !map.containsKey(key)) {
                    return defaultValue;
                }
                cur = map.get(key);
            }
        }
        return cur;
    }

    static List<Object> parsePath(String path) {
        List<Object> tokens = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return tokens;
        }
        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '.') {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
                i++;
                continue;
            }
            if (ch == '[') {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
                int j = path.indexOf(']', i + 1);
                if (j < 0) {
                    throw new IllegalArgumentException("Invalid path (missing ']'): " + path);
                }
                String idxStr = path.substring(i + 1, j).trim();
                if (!idxStr.chars().allMatch(Character::isDigit)) {
                    throw new IllegalArgumentException("Invalid list index in path: " + path);
                }
                tokens.add(Integer.parseInt(idxStr));
                i = j + 1;
                continue;
            }
            buf.append(ch);
            i++;
        }
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }
        return tokens;
    }
}
