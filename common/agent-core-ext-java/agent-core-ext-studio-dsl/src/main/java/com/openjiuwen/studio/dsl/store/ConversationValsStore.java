/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.store;

import java.util.Map;

/**
 * Conversation-scoped key/value store used by Start / SetVariable (Python {@code global.vals.*} Redis).
 *
 * @since 2026-08-25
 */

public interface ConversationValsStore {

    /**
     * Load JSON object map for key; empty map when missing or on soft failure.
     *
     * @param key key
     * @return result
     */

    Map<String, Object> getMap(String key);

    /**
     * Persist JSON object map with TTL seconds (Python conversation variable TTL).
     *
     * @param key key
     * @param values values
     * @param ttlSeconds ttlSeconds
     */

    void setMap(String key, Map<String, Object> values, long ttlSeconds);
}
