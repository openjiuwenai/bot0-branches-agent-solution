/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Jedis-backed store matching Python workflow Start Redis keys ({@code global.vals.*}).
 *
 * @since 2026-08-25
 */

public final class JedisConversationValsStore implements ConversationValsStore {
    private static final Logger LOG = Logger.getLogger(JedisConversationValsStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JedisPooled jedis;

    /**
     * JedisConversationValsStore.
     *
     * @param jedis jedis
     */

    public JedisConversationValsStore(JedisPooled jedis) {
        this.jedis = jedis;
    }

    /**
     * From host/port (Python redis_manager style host config).
     *
     * @param host host
     * @param port port
     * @return result
     */

    public static JedisConversationValsStore connect(String host, int port) {
        return new JedisConversationValsStore(new JedisPooled(host, port));
    }
    @Override
    public Map<String, Object> getMap(String key) {
        try {
            String raw = jedis.get(key);
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            Map<String, Object> parsed = MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException | JedisException e) {
            LOG.log(Level.WARNING, "Failed to get session vals from redis: " + e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * setMap.
     *
     * @param key key
     * @param values values
     * @param ttlSeconds ttlSeconds
     * @since 0.1.0
     */

    @Override
    public void setMap(String key, Map<String, Object> values, long ttlSeconds) {
        try {
            String json = MAPPER.writeValueAsString(values == null ? Map.of() : values);
            long ttl = Math.max(1L, ttlSeconds);
            jedis.setex(key, ttl, json);
        } catch (JsonProcessingException | JedisException e) {
            LOG.log(Level.WARNING, "Failed to save session vals to redis: " + e.getMessage(), e);
        }
    }
}
