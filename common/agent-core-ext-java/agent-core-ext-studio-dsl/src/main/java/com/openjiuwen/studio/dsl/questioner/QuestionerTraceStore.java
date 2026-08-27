/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.store.SharedJedisPool;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Questioner node Redis / in-memory trace store
 * (Python {@code agent_runtime...questioner.QuestionerTraceStore}).
 *
 * <p>Key: {@code agentBuilder:questioner:trace:{sessionId}:{componentId}}.
 * Soft-fails when Redis is unavailable (falls back to process memory).
 *
 * @since 2026-08-26
 */

public final class QuestionerTraceStore {

    /**
     * KEY_PREFIX.
     *
     * @since 0.1.0
     */

    public static final String KEY_PREFIX = "agentBuilder:questioner:trace";
    private static final Logger LOG = Logger.getLogger(QuestionerTraceStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int DEFAULT_TTL_SECONDS = 86400;
    private static final int MEMORY_MAX_ENTRIES_PER_KEY = 512;
    private static final int MEMORY_MAX_KEYS = 1024;

    private static final ConcurrentHashMap<String, List<Map<String, Object>>> MEMORY = new ConcurrentHashMap<>();
    private static volatile JedisPooled jedisOverride;

    private QuestionerTraceStore() {}

    /**
     * Test / host: inject Jedis; null clears.
     *
     * @param jedis jedis
     * @since 0.1.0
     */

    public static void setJedis(JedisPooled jedis) {
        jedisOverride = jedis;
    }

    /**
     * Clear in-memory traces (tests).
     *
     * @since 0.1.0
     *
     */

    public static void clearMemory() {
        MEMORY.clear();
    }

    /**
     * buildKey.
     *
     * @param sessionId sessionId
     * @param componentId componentId
     * @return result
     * @since 0.1.0
     */

    public static String buildKey(String sessionId, String componentId) {
        return KEY_PREFIX + ":" + nullToEmpty(sessionId) + ":" + nullToEmpty(componentId);
    }

    /**
     * append.
     *
     * @param sessionId sessionId
     * @param componentId componentId
     * @param traceData traceData
     * @since 0.1.0
     */

    public static void append(String sessionId, String componentId, Map<String, Object> traceData) {
        if (traceData == null || traceData.isEmpty()) {
        return;
    }
        String key = buildKey(sessionId, componentId);
        JedisPooled jedis = resolveJedis();
        if (jedis != null) {
            try {
                String json = MAPPER.writeValueAsString(traceData);
                jedis.rpush(key, json);
                jedis.expire(key, DEFAULT_TTL_SECONDS);
                return;
            } catch (JsonProcessingException | JedisException e) {
                LOG.log(Level.WARNING, "Failed to append questioner trace to Redis: " + e.getMessage(), e);
            }
        }
        MEMORY.computeIfAbsent(key, k -> {
            if (MEMORY.size() >= MEMORY_MAX_KEYS) {
                MEMORY.clear();
            }
            return new ArrayList<>();
        }).add(new LinkedHashMap<>(traceData));
        trimMemoryList(key);
    }

    private static void trimMemoryList(String key) {
        List<Map<String, Object>> list = MEMORY.get(key);
        if (list != null && list.size() > MEMORY_MAX_ENTRIES_PER_KEY) {
            list.subList(0, list.size() - MEMORY_MAX_ENTRIES_PER_KEY).clear();
        }
    }

    public static List<Map<String, Object>> getAll(String sessionId, String componentId) {
        String key = buildKey(sessionId, componentId);
        JedisPooled jedis = resolveJedis();
        if (jedis != null) {
            try {
                List<String> raw = jedis.lrange(key, 0, -1);
                List<Map<String, Object>> out = new ArrayList<>();
                if (raw != null) {
                    for (String item : raw) {
                        try {
                            Map<String, Object> m = MAPPER.readValue(item, MAP_TYPE);
                            if (m != null) {
                                out.add(m);
                            }
                        } catch (JsonProcessingException ignored) {
                            // skip bad entry
                        }
                    }
                }
                return out;
            } catch (JedisException e) {
                LOG.log(Level.WARNING, "Failed to get questioner trace from Redis: " + e.getMessage(), e);
            }
        }
        List<Map<String, Object>> mem = MEMORY.get(key);
        if (mem == null) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> m : mem) {
            copy.add(new LinkedHashMap<>(m));
        }
        return copy;
    }

    /**
     * delete.
     *
     * @param sessionId sessionId
     * @param componentId componentId
     * @since 0.1.0
     */

    public static void delete(String sessionId, String componentId) {
        String key = buildKey(sessionId, componentId);
        JedisPooled jedis = resolveJedis();
        if (jedis != null) {
            try {
                jedis.del(key);
            } catch (JedisException e) {
                LOG.log(Level.WARNING, "Failed to delete questioner trace from Redis: " + e.getMessage(), e);
            }
        }
        MEMORY.remove(key);
    }

    /**
     * * Recover stored traces onto session tracer (Python {@code recover_to_session}).
     *
     * @param sessionId sessionId
     * @param componentId componentId
     * @param session session
     * @since 0.1.0
     */

    public static void recoverToSession(String sessionId, String componentId, NodeSessionApi session) {
        if (session == null) {
        return;
    }
        for (Map<String, Object> item : getAll(sessionId, componentId)) {
            try {
                session.trace(item);
            } catch (IllegalStateException | ClassCastException | NullPointerException
                    | IllegalArgumentException | IndexOutOfBoundsException ignored) {
                // mock / skipTrace
            }
        }
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
