/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * KB outbound customer header rename (Python {@code customer_header_inject.inject_customer_headers_to_kb}).
 *
 * @since 2026-08-27
 */

public final class CustomerHeaderInject {
    private static volatile boolean enabled = true;

    private CustomerHeaderInject() {}

    /**
     * Host may disable rename (tests).
     *
     * @param on on
     * @since 0.1.0
     */

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Project captured customer headers onto KB HTTP headers.
     *
     * @param headers mutable outbound headers
     */

    public static void applyToKb(Map<String, String> headers) {
        if (!enabled || headers == null || headers.isEmpty()) {
        return;
    }
        Map<String, String> captured = KnowledgeRequestContext.headers();
        if (captured.isEmpty()) {
            return;
        }
        Map<String, String> projected = project(captured);
        headers.putAll(projected);
    }

    static Map<String, String> project(Map<String, String> captured) {
        Map<String, String> out = new LinkedHashMap<>();
        mapIfPresent(captured, out, "cust-userid", "userId");
        mapIfPresent(captured, out, "cust-token", "token");
        mapIfPresent(captured, out, "cust-tenantid", "tenantId");
        mapIfPresent(captured, out, "cust-appid", "appId");
        // pass-through x-auth-token when present
        mapIfPresent(captured, out, "x-auth-token", "x-auth-token");
        return out;
    }

    private static void mapIfPresent(Map<String, String> from, Map<String, String> to, String src, String dst) {
        String v = lookupIgnoreCase(from, src);
        if (v != null && !v.isBlank()) {
            to.put(dst, v);
        }
    }

    private static String lookupIgnoreCase(Map<String, String> map, String key) {
        if (map.containsKey(key)) {
        return map.get(key);
    }
        String lower = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }
}
