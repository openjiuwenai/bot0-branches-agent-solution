/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.studio.dsl.store.SharedJedisPool;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Redis / in-memory cache for knowledge retrieval image & file virtual IDs
 * (Python {@code KNOWLEDGE_IMAGE_CACHE_PREFIX} / {@code KNOWLEDGE_FILE_CACHE_PREFIX}).
 *
 * @since 2026-08-26
 */

public final class KnowledgeRetrievalCacheStore {
    public static final String IMAGE_PREFIX = "knowledge:image:";
    public static final String FILE_PREFIX = "knowledge:file:";

    private static final Logger LOG = Logger.getLogger(KnowledgeRetrievalCacheStore.class.getName());
    private static final ConcurrentHashMap<String, String> MEMORY = new ConcurrentHashMap<>();
    private static volatile JedisPooled jedisOverride;

    private KnowledgeRetrievalCacheStore() {}

    /**
     * Test / host: inject Jedis; null clears override.
     *
     * @param jedis jedis
     * @since 0.1.0
     */
    public static void setJedis(JedisPooled jedis) {
        jedisOverride = jedis;
    }

    /**
     * Clear in-memory cache (tests).
     *
     * @since 0.1.0
     */
    public static void clearMemory() {
        MEMORY.clear();
    }

    /** @return TTL seconds from env {@code KNOWLEDGE_RETRIEVAL_IMAGE_VALIDITY_DAYS} (default 7). */
    public static int defaultTtlSeconds() {
        int days = 7;
        String env = System.getenv("KNOWLEDGE_RETRIEVAL_IMAGE_VALIDITY_DAYS");
        if (env == null || env.isBlank()) {
            env = System.getProperty("knowledge.retrieval.image.validity.days");
        }
        if (env != null && !env.isBlank()) {
            try {
                days = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                days = 7;
            }
        }
        return Math.max(days, 1) * 24 * 60 * 60;
    }

    /**
     * set.
     *
     * @param key full redis key
     * @param value cache payload
     * @param ttlSeconds TTL
     */

    public static void set(String key, String value, int ttlSeconds) {
        if (key == null || key.isBlank() || value == null) {
        return;
    }
        JedisPooled jedis = resolveJedis();
        if (jedis != null) {
            try {
                jedis.setex(key, ttlSeconds, value);
                return;
            } catch (JedisException e) {
                LOG.log(Level.WARNING, "Failed to cache knowledge key=" + key + ": " + e.getMessage(), e);
            }
        }
        MEMORY.put(key, value);
    }

    /** @return cached value or null */
    public static String get(String key) {
        JedisPooled jedis = resolveJedis();
        if (jedis != null) {
            try {
                return jedis.get(key);
            } catch (JedisException e) {
                LOG.log(Level.WARNING, "Failed to read knowledge cache key=" + key + ": " + e.getMessage(), e);
            }
        }
        return MEMORY.get(key);
    }

    /**
     * Expose memory map for tests.
     *
     * @return result
     * @since 0.1.0
     */
    static Map<String, String> memorySnapshot() {
        return Map.copyOf(MEMORY);
    }

    private static JedisPooled resolveJedis() {
        JedisPooled o = jedisOverride;
        if (o != null) {
            return o;
        }
        String host = firstNonBlank(System.getenv("REDIS_HOST"), System.getProperty("studio.dsl.redis.host"));
        if (host == null) {
            return null;
        }
        try {
            int port = 6379;
            String p = firstNonBlank(System.getenv("REDIS_PORT"), System.getProperty("studio.dsl.redis.port"));
            if (p != null) {
                port = Integer.parseInt(p.trim());
            }
            return SharedJedisPool.getOrConnect(host.trim(), port);
        } catch (NumberFormatException | JedisException e) {
            return null;
        }
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
}
