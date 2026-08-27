/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.store;

import redis.clients.jedis.exceptions.JedisException;

/**
 * Process-wide store accessor (Python {@code get_redis_client()} analogue).
 *
 * <p>Resolution order: explicit {@link #setDefault}, else Jedis from {@code REDIS_HOST} /
 * {@code studio.dsl.redis.host}, else in-memory.
 *
 * @since 2026-08-25
 */

public final class ConversationValsStores {
    private static final InMemoryConversationValsStore MEMORY = new InMemoryConversationValsStore();
    private static volatile ConversationValsStore override;
    private static volatile ConversationValsStore jedisStore;
    private static volatile String jedisStoreKey;

    private ConversationValsStores() {}

    /**
     * setDefault.
     *
     * @param store store (null clears override)
     */

    public static void setDefault(ConversationValsStore store) {
        override = store;
    }

    /**
     * get — same role as Python workflow {@code get_redis_client()}.
     *
     * @return result
     */

    public static ConversationValsStore get() {
        ConversationValsStore o = override;
        if (o != null) {
            return o;
        }
        String host = firstNonBlank(
                System.getenv("REDIS_HOST"), System.getProperty("studio.dsl.redis.host"));
        if (host != null) {
            try {
                int port = parsePort(
                        firstNonBlank(System.getenv("REDIS_PORT"), System.getProperty("studio.dsl.redis.port")),
                        6379);
                String key = host + ":" + port;
                ConversationValsStore cached = jedisStore;
                if (cached != null && key.equals(jedisStoreKey)) {
                    return cached;
                }
                synchronized (ConversationValsStores.class) {
                    if (jedisStore != null && key.equals(jedisStoreKey)) {
                        return jedisStore;
                    }
                    jedisStore = new JedisConversationValsStore(SharedJedisPool.getOrConnect(host, port));
                    jedisStoreKey = key;
                    return jedisStore;
                }
            } catch (NumberFormatException | JedisException ignored) {
                // fall through to memory — Python Start also soft-fails redis errors
            }
        }
        return MEMORY;
    }

    /**
     * memoryStore — shared in-memory instance for tests.
     *
     * @return result
     */

    public static InMemoryConversationValsStore memoryStore() {
        return MEMORY;
    }
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
        return a.trim();
    }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
        return fallback;
    }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
