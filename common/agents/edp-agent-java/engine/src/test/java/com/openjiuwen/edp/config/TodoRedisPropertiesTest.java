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

package com.openjiuwen.edp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TodoRedisProperties 单元测试。
 *
 * <p>验证 Redis 连接配置的字段绑定，确认 TodoConfig 子类（key-prefix/ttl-seconds/refresh-on-read）
 * 已删除，仅保留连接参数和 checkpointer-ttl-minutes。</p>
 */
class TodoRedisPropertiesTest {
    @Test
    void defaults_areCorrect() {
        TodoRedisProperties props = new TodoRedisProperties();
        assertEquals("single", props.getMode());
        assertEquals("localhost", props.getHost());
        assertEquals(6379, props.getPort());
        assertNull(props.getPassword());
        assertEquals(0, props.getDatabase());
        assertEquals(5000, props.getConnectTimeoutMs());
        assertEquals(10000, props.getSocketTimeoutMs());
        assertEquals(60, props.getCheckpointerTtlMinutes());
    }

    @Test
    void setters_updateValues() {
        TodoRedisProperties props = new TodoRedisProperties();
        props.setMode("cluster");
        props.setHost("redis.example.com");
        props.setPort(6380);
        props.setPassword("secret");
        props.setDatabase(3);
        props.setConnectTimeoutMs(3000);
        props.setSocketTimeoutMs(8000);
        props.setCheckpointerTtlMinutes(120);

        assertEquals("cluster", props.getMode());
        assertEquals("redis.example.com", props.getHost());
        assertEquals(6380, props.getPort());
        assertEquals("secret", props.getPassword());
        assertEquals(3, props.getDatabase());
        assertEquals(3000, props.getConnectTimeoutMs());
        assertEquals(8000, props.getSocketTimeoutMs());
        assertEquals(120, props.getCheckpointerTtlMinutes());
    }

    @Test
    void sentinelConfig_defaults() {
        TodoRedisProperties props = new TodoRedisProperties();
        assertNotNull(props.getSentinel());
        assertNull(props.getSentinel().getMaster());
        assertTrue(props.getSentinel().getNodes().isEmpty());
        assertNull(props.getSentinel().getPassword());
    }

    @Test
    void clusterConfig_defaults() {
        TodoRedisProperties props = new TodoRedisProperties();
        assertNotNull(props.getCluster());
        assertTrue(props.getCluster().getNodes().isEmpty());
        assertEquals(3, props.getCluster().getMaxRedirects());
    }

    @Test
    void sentinelConfig_setters() {
        TodoRedisProperties props = new TodoRedisProperties();
        TodoRedisProperties.SentinelConfig sentinel = props.getSentinel();
        sentinel.setMaster("mymaster");
        sentinel.setNodes(java.util.List.of("host1:26379", "host2:26379"));
        sentinel.setPassword("sentinel-pwd");

        assertEquals("mymaster", sentinel.getMaster());
        assertEquals(2, sentinel.getNodes().size());
        assertEquals("sentinel-pwd", sentinel.getPassword());
    }

    @Test
    void clusterConfig_setters() {
        TodoRedisProperties props = new TodoRedisProperties();
        TodoRedisProperties.ClusterConfig cluster = props.getCluster();
        cluster.setNodes(java.util.List.of("host1:7000", "host2:7000", "host3:7000"));
        cluster.setMaxRedirects(5);

        assertEquals(3, cluster.getNodes().size());
        assertEquals(5, cluster.getMaxRedirects());
    }
}
