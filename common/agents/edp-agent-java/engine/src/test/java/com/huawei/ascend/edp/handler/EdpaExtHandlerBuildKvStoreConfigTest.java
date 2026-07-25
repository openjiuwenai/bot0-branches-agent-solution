/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
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

package com.huawei.ascend.edp.handler;

import static org.junit.jupiter.api.Assertions.*;

import com.huawei.ascend.edp.config.RedisConfig;
import com.huawei.ascend.edp.config.TodoRedisProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * EdpaExtHandler.buildKvStoreConfig() 单元测试。
 *
 * <p>验证 agent-core KV 存储配置的正确构建：
 * <ul>
 *   <li>RedisProperties 为 null 时返回 null（回落 file 存储）</li>
 *   <li>single 模式：conf 包含 host/port，不含 cluster</li>
 *   <li>cluster 模式：conf 包含 cluster="true"</li>
 *   <li>有密码时包含 password，无密码时不包含</li>
 * </ul>
 */
class EdpaExtHandlerBuildKvStoreConfigTest {

    private TodoRedisProperties props;

    @BeforeEach
    void setUp() throws Exception {
        props = new TodoRedisProperties();
        props.setHost("localhost");
        props.setPort(6379);
        props.setPassword(null);
        props.setMode("single");
        injectStatic("singletonProps", props);
    }

    @AfterEach
    void tearDown() throws Exception {
        injectStatic("singletonProps", null);
    }

    private void injectStatic(String fieldName, Object value) throws Exception {
        Field f = RedisConfig.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildKvStoreConfig() throws Exception {
        Method m = EdpaExtHandler.class.getDeclaredMethod("buildKvStoreConfig");
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(null);
    }

    @Test
    void nullProps_returnsNull() throws Exception {
        injectStatic("singletonProps", null);
        assertNull(invokeBuildKvStoreConfig());
    }

    @Test
    void singleMode_correctConfig() throws Exception {
        Map<String, Object> result = invokeBuildKvStoreConfig();
        assertNotNull(result);
        assertEquals("redis", result.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) result.get("conf");
        assertEquals("localhost", conf.get("host"));
        assertEquals(6379, conf.get("port"));
        assertNull(conf.get("password"));
        assertNull(conf.get("cluster"));
    }

    @Test
    void withPassword_includesPassword() throws Exception {
        props.setPassword("secret123");
        Map<String, Object> result = invokeBuildKvStoreConfig();
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) result.get("conf");
        assertEquals("secret123", conf.get("password"));
    }

    @Test
    void blankPassword_notIncluded() throws Exception {
        props.setPassword("   ");
        Map<String, Object> result = invokeBuildKvStoreConfig();
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) result.get("conf");
        assertNull(conf.get("password"));
    }

    @Test
    void clusterMode_includesClusterFlag() throws Exception {
        props.setMode("cluster");
        Map<String, Object> result = invokeBuildKvStoreConfig();
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) result.get("conf");
        assertEquals("true", conf.get("cluster"));
    }

    @Test
    void sentinelMode_fallsBackToSingle() throws Exception {
        props.setMode("sentinel");
        Map<String, Object> result = invokeBuildKvStoreConfig();
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) result.get("conf");
        assertNull(conf.get("cluster"));
    }
}
