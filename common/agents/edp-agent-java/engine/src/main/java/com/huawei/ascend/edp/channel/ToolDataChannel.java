/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨工具数据通道。
 *
 * 对齐 Python 解耦版 rail/tool_data_channel.py 的单字段设计（ADR-006）。
 * 外层用 ToolDataKey 四元组隔离（多租户支持），
 * 内层存储改为单字段 dict 结构。
 * 禁止单独维护 key 索引列表。
 *
 * <p>数据流：VersatileInterruptRail → ToolDataChannel.store(result_key) →
 * McpInterruptRail → ToolDataChannel.hit(input_key)。
 * 对齐 Python tool_data_channel.py: 工具间数据透传通道。</p>
 *
 * @since 2024-01-01
 */

public class ToolDataChannel {
    // 对齐 Python tool_data_channel.py: store/remove/clear 确认日志
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolDataChannel.class);

    private final ConcurrentHashMap<ToolDataKey, ConcurrentHashMap<String, Object>> channel = new ConcurrentHashMap<>();

    /**
     * 存储 Map 数据。
     *
     * @param key 四元组隔离键
     * @param resultKey 单字段结果键
     * @param data 数据值
     */

    public void store(ToolDataKey key, String resultKey, Map<String, Object> data) {
        store(key, resultKey, (Object) data);
    }

    /**
     * 存储任意类型数据。
     *
     * @param key 四元组隔离键
     * @param resultKey 单字段结果键
     * @param data 数据值
     */

    public void store(ToolDataKey key, String resultKey, Object data) {
        if (key == null || resultKey == null || resultKey.isBlank()) {
            LOGGER.debug("[ToolDataChannel] store: rejected null/blank key or resultKey");
            return;
        }
        channel.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(resultKey, data);
        LOGGER.info("[ToolDataChannel] store: key={} resultKey={}", key, resultKey);
    }

    /**
     * 获取 Map 数据。
     *
     * @param key 四元组隔离键
     * @param resultKey 单字段结果键
     * @return 数据值，null 表示不存在或不是 Map
     */

    public Object get(ToolDataKey key, String resultKey) {
        Object value = getObject(key, resultKey).orElse(null);
        return value instanceof Map<?, ?> map ? toStringKeyMap(map) : Collections.emptyMap();
    }

    /**
     * 获取任意类型数据。
     */

    public Optional<Object> getObject(ToolDataKey key, String resultKey) {
        if (key == null || resultKey == null || resultKey.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        return scope != null ? Optional.ofNullable(scope.get(resultKey)) : Optional.empty();
    }

    /**
     * 判断数据是否存在。
     */

    public boolean contains(ToolDataKey key, String resultKey) {
        if (key == null || resultKey == null || resultKey.isBlank()) {
            return false;
        }
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        return scope != null && scope.containsKey(resultKey);
    }

    /** Snapshot. */
    public Map<String, Object> snapshot(ToolDataKey key) {
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        return scope == null ? Map.of() : new LinkedHashMap<>(scope);
    }

    /**
     * 移除数据。
     */

    public void remove(ToolDataKey key, String resultKey) {
        LOGGER.info("[ToolDataChannel] remove: resultKey={}", resultKey);
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        if (scope != null) {
            scope.remove(resultKey);
            if (scope.isEmpty()) {
                channel.remove(key);
            }
        }
    }

    /**
     * 清理指定 key 的所有数据。
     */

    public void clear(ToolDataKey key) {
        LOGGER.info("[ToolDataChannel] clear: key={}", key);
        channel.remove(key);
    }

    /**
     * 清理所有数据。
     */

    public void clearAll() {
        LOGGER.info("[ToolDataChannel] clearAll: all data removed");
        channel.clear();
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
