/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory stand-in for Redis when no client is configured (tests / local).
 *
 * @since 2026-08-25
 */

public final class InMemoryConversationValsStore implements ConversationValsStore {
    private final ConcurrentHashMap<String, Map<String, Object>> data = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> getMap(String key) {
        Map<String, Object> v = data.get(key);
        return v == null ? Map.of() : new LinkedHashMap<>(v);
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
        data.put(key, new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    /**
     * * clear.
     *
     * @since 0.1.0
     */
    public void clear() {
        data.clear();
    }
}
