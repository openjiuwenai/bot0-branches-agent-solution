/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.store;

import redis.clients.jedis.JedisPooled;

/**
 * Process-wide memoized {@link JedisPooled} (host:port keyed).
 *
 * @since 2026-08-27
 */

public final class SharedJedisPool {
    private static volatile JedisPooled cached;
    private static volatile String cacheKey;

    private SharedJedisPool() {}

    /**
     * Resolve shared pool for host/port.
     *
     * @param host redis host
     * @param port redis port
     * @return pooled client
     */

    public static JedisPooled getOrConnect(String host, int port) {
        String key = host + ":" + port;
        JedisPooled hit = cached;
        if (hit != null && key.equals(cacheKey)) {
            return hit;
        }
        synchronized (SharedJedisPool.class) {
            if (cached != null && key.equals(cacheKey)) {
                return cached;
            }
            cached = new JedisPooled(host, port);
            cacheKey = key;
            return cached;
        }
    }

    /**
     * Test hook — clear memoized client.
     *
     * @since 0.1.0
     *
     */

    static void resetForTests() {
        synchronized (SharedJedisPool.class) {
            cached = null;
            cacheKey = null;
        }
    }
}
