/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request HTTP headers for KB auth merge (Python {@code _request_ctx.headers}).
 *
 * @since 2026-08-26
 */

public final class KnowledgeRequestContext {
    private static final ThreadLocal<Map<String, String>> HEADERS = new ThreadLocal<>();

    private KnowledgeRequestContext() {}

    /**
     * setHeaders.
     *
     * @param headers request headers (case-insensitive keys normalized to lower)
     */

    public static void setHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            HEADERS.remove();
            return;
        }
        Map<String, String> norm = new LinkedHashMap<>();
        headers.forEach((k, v) -> norm.put(k == null ? "" : k.toLowerCase(), v));
        HEADERS.set(Collections.unmodifiableMap(norm));
    }

    /** @return current request headers or empty */
    public static Map<String, String> headers() {
        Map<String, String> h = HEADERS.get();
        return h == null ? Map.of() : h;
    }

    /**
     * clear thread-local headers (tests).
     *
     * @since 0.1.0
     */
    public static void clear() {
        HEADERS.remove();
    }
    static String authToken() {
        Map<String, String> h = headers();
        String t = h.get("x-auth-token");
        return t == null ? "" : t;
    }
}
