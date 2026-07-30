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

package com.openjiuwen.edp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.edp.config.ActRuleConfig;
import com.openjiuwen.edp.config.RedisConfig;
import com.openjiuwen.edp.config.TodoRedisProperties;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExecutionLimitRail 单元测试。
 *
 * <p>覆盖场景：Redis 恢复、本地递增、超限拦截、持久化、异常降级、定期清理。</p>
 */
class ExecutionLimitRailTest {
    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private TodoRedisProperties props;

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        props = new TodoRedisProperties();
        props.setCheckpointerTtlMinutes(60);

        injectStatic("singletonTemplate", redisTemplate);
        injectStatic("singletonProps", props);
    }

    @AfterEach
    void tearDown() throws Exception {
        injectStatic("singletonTemplate", null);
        injectStatic("singletonProps", null);
    }

    /**
     * 通过反射设置 RedisConfig 的静态字段值。
     *
     * @param fieldName 字段名称
     * @param value     要设置的值
     * @throws Exception 反射操作异常
     */
    private void injectStatic(String fieldName, Object value) throws Exception {
        Field f = RedisConfig.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, value);
    }

    /**
     * 构造 mock AgentCallbackContext（可 verify requestForceFinish）。
     *
     * @param sid       会话 ID
     * @param toolName 工具名称
     * @return mock 的 AgentCallbackContext
     */
    private AgentCallbackContext mockCtx(String sid, String toolName) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sid);
        ToolCallInputs inputs = ToolCallInputs.builder().toolName(toolName).toolArgs(Map.of()).build();
        return mock(AgentCallbackContext.class);
    }

    /**
     * 构造真实 AgentCallbackContext（不可 verify，但可执行）。
     *
     * @param sid       会话 ID
     * @param toolName 工具名称
     * @return 真实的 AgentCallbackContext
     */
    private AgentCallbackContext realCtx(String sid, String toolName) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sid);
        ToolCallInputs inputs = ToolCallInputs.builder().toolName(toolName).toolArgs(Map.of()).build();
        return AgentCallbackContext.builder().session(session).inputs(inputs).build();
    }

    /**
     * 构造无工具的真实 AgentCallbackContext（用于 beforeInvoke/afterInvoke）。
     *
     * @param sid 会话 ID
     * @return 无工具的 AgentCallbackContext
     */
    private AgentCallbackContext realCtxNoTool(String sid) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sid);
        return AgentCallbackContext.builder().session(session).build();
    }

    /**
     * 构造 mock AgentCallbackContext 并 stub getInputs/getSession 方法。
     *
     * @param sid       会话 ID
     * @param toolName 工具名称
     * @return stub 了 getInputs/getSession 的 mock AgentCallbackContext
     */
    private AgentCallbackContext mockCtxWithStubs(String sid, String toolName) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sid);
        ToolCallInputs inputs = ToolCallInputs.builder().toolName(toolName).toolArgs(Map.of()).build();
        AgentCallbackContext ctx = mock(AgentCallbackContext.class);
        when(ctx.getSession()).thenReturn(session);
        when(ctx.getInputs()).thenReturn(inputs);
        return ctx;
    }

    /**
     * 构造无工具的 mock AgentCallbackContext。
     *
     * @param sid 会话 ID
     * @return 无工具的 mock AgentCallbackContext
     */
    private AgentCallbackContext mockCtxNoTool(String sid) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sid);
        AgentCallbackContext ctx = mock(AgentCallbackContext.class);
        when(ctx.getSession()).thenReturn(session);
        return ctx;
    }

    /**
     * 构建带工具调用次数限制的 ActRuleConfig。
     *
     * @param limits 工具名称到调用次数上限的映射
     * @return 配置好的 ActRuleConfig
     */
    private ActRuleConfig buildActRule(Map<String, Integer> limits) {
        ActRuleConfig actrule = new ActRuleConfig();
        actrule.setToolLimits(limits);
        return actrule;
    }

    // TC-01: 新会话 Redis 无数据，从 0 开始
    @Test
    void newSession_redisEmpty_startsFromZero() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx);

        verify(ctx, never()).requestForceFinish(any());
    }

    // TC-02: 会话恢复 - Redis 有数据
    @Test
    void recoveredSession_startsFromRedisValue() {
        Map<Object, Object> redisData = new HashMap<>();
        redisData.put("call_versatile", "40");
        when(hashOps.entries(anyString())).thenReturn(redisData);

        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx); // 恢复 40 + 1 = 41

        verify(ctx, never()).requestForceFinish(any()); // 41 <= 50
    }

    // TC-03: 超限拦截
    @Test
    void exceedLimit_forceFinish() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 2));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 1
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 2
        AgentCallbackContext ctx3 = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx3); // 3 > 2

        verify(ctx3).requestForceFinish(any());
    }

    // TC-04: 不同工具独立计数
    @Test
    void differentTools_countSeparately() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 3, "call_mcp", 3));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 1
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 2
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 3
        AgentCallbackContext ctx4 = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx4); // 4 > 3 -> 拦截
        verify(ctx4).requestForceFinish(any());

        AgentCallbackContext ctx5 = mockCtxWithStubs("session-1", "call_mcp");
        rail.beforeToolCall(ctx5); // 1，不拦截
        verify(ctx5, never()).requestForceFinish(any());
    }

    // TC-05: 不同会话独立计数
    @Test
    void differentSessions_countIndependently() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 3));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 1
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 2
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 3
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx); // 4 > 3 -> 拦截
        verify(ctx).requestForceFinish(any());

        rail.beforeInvoke(realCtxNoTool("session-2"));
        AgentCallbackContext ctx2 = mockCtxWithStubs("session-2", "call_versatile");
        rail.beforeToolCall(ctx2); // 1，不拦截
        verify(ctx2, never()).requestForceFinish(any());
    }

    // TC-06: 持久化 - 正常保存
    @Test
    void afterInvoke_persistsToRedis() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile"));
        rail.beforeToolCall(realCtx("session-1", "call_mcp"));
        rail.afterInvoke(realCtxNoTool("session-1"));

        verify(hashOps).putAll(eq("edpa:toolcount:session-1"), anyMap());
        verify(redisTemplate).expire(eq("edpa:toolcount:session-1"), any(Duration.class));
    }

    // TC-07: 跨会话累加
    @Test
    void crossSession_accumulatesFromRedis() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 1

        Map<String, String> savedData = new HashMap<>();
        doAnswer((InvocationOnMock inv) -> {
            savedData.putAll(inv.getArgument(1));
            return null;
        }).when(hashOps).putAll(anyString(), anyMap());

        rail.afterInvoke(realCtxNoTool("session-1"));
        assertEquals("1", savedData.get("call_versatile"));

        // 第二次会话从 Redis 恢复
        Map<Object, Object> redisData = new HashMap<>();
        redisData.put("call_versatile", savedData.get("call_versatile"));
        when(hashOps.entries(anyString())).thenReturn(redisData);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx); // 1+1=2
        verify(ctx, never()).requestForceFinish(any());
    }

    // TC-08: Redis GET 异常 - 不阻断业务
    @Test
    void redisGetException_doesNotBlock() {
        when(hashOps.entries(anyString())).thenThrow(new RedisConnectionFailureException("Redis down"));
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx); // 从 0 开始，count=1
        verify(ctx, never()).requestForceFinish(any());
    }

    // TC-09: Redis SET 异常 - 不阻断业务
    @Test
    void redisSetException_doesNotThrow() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        doThrow(new RedisConnectionFailureException("Redis down")).when(hashOps).putAll(anyString(), anyMap());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile"));

        assertDoesNotThrow(() -> rail.afterInvoke(realCtxNoTool("session-1")));
    }

    // TC-10: 空会话 afterInvoke
    @Test
    void emptySession_afterInvoke_noRedisWrite() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.afterInvoke(realCtxNoTool("session-1"));

        verify(hashOps, never()).putAll(anyString(), anyMap());
    }

    // TC-11: 防御性清理 - beforeInvoke 清除残留
    @Test
    void beforeInvoke_clearsStaleEntry() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 2));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile"));
        // 不调用 afterInvoke，模拟崩溃

        // 再次 beforeInvoke 清除残留，从 0 开始
        rail.beforeInvoke(realCtxNoTool("session-1"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-1", "call_versatile");
        rail.beforeToolCall(ctx); // 从 0 开始，count=1
        verify(ctx, never()).requestForceFinish(any()); // 1 <= 2
    }

    // TC-12: 定期清理僵尸 entry
    @Test
    void cleanupStaleEntries_removesOldSessions() throws Exception {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 10000));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));
        rail.beforeToolCall(realCtx("session-1", "call_versatile"));

        rail.beforeInvoke(realCtxNoTool("session-2"));
        rail.beforeToolCall(realCtx("session-2", "call_versatile"));

        // session-2 设为 2 小时前（僵尸）
        Field lastActiveField = ExecutionLimitRail.class.getDeclaredField("sessionLastActive");
        lastActiveField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> sessionLastActive = (Map<String, Long>) lastActiveField.get(rail);
        sessionLastActive.put("session-2", System.currentTimeMillis() - 7200000L);

        // callCounter 设为 99，下一次触发清理
        Field callCounterField = ExecutionLimitRail.class.getDeclaredField("callCounter");
        callCounterField.setAccessible(true);
        Object callCounterRaw = callCounterField.get(rail);
        assertTrue(callCounterRaw instanceof AtomicInteger,
                "callCounter should be AtomicInteger");
        AtomicInteger callCounter = (AtomicInteger) callCounterRaw;
        callCounter.set(99);

        rail.beforeToolCall(realCtx("session-1", "call_versatile")); // 100th call -> cleanup

        assertFalse(sessionLastActive.containsKey("session-2"));
        assertTrue(sessionLastActive.containsKey("session-1"));
    }

    // TC-13: null session 兜底
    @Test
    void nullSession_usesDefaultKey() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn("state:default:edp_agent:uuid-xxx");
        ToolCallInputs inputs = ToolCallInputs.builder().toolName("call_versatile").toolArgs(Map.of()).build();
        AgentCallbackContext ctx = mock(AgentCallbackContext.class);
        when(ctx.getSession()).thenReturn(session);
        when(ctx.getInputs()).thenReturn(inputs);

        rail.beforeInvoke(realCtxNoTool("state:default:edp_agent:uuid-xxx"));
        rail.beforeToolCall(ctx);
        verify(ctx, never()).requestForceFinish(any());
    }

    // TC-14: 并发安全 - 多线程同时调用
    @Test
    void concurrentCalls_threadSafe() throws Exception {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 1000));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-1"));

        int threadCount = 10;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount, 60L,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(threadCount));
        try {
            for (int i = 0; i < threadCount; i++) {
                executor.execute(() -> rail.beforeToolCall(realCtx("session-1", "call_versatile")));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        rail.afterInvoke(realCtxNoTool("session-1"));

        verify(hashOps).putAll(eq("edpa:toolcount:session-1"),
                org.mockito.ArgumentMatchers.argThat(m -> "10".equals(((Map<?, ?>) m).get("call_versatile"))));
    }

    // TC-15: redisTemplate 为 null 时降级——计数从 0 开始，不崩溃
    @Test
    void nullRedisTemplate_degradesGracefully() throws Exception {
        injectStatic("singletonTemplate", null);
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("session-null"));
        AgentCallbackContext ctx = mockCtxWithStubs("session-null", "call_versatile");
        rail.beforeToolCall(ctx); // 从 0 开始，count=1
        verify(ctx, never()).requestForceFinish(any());

        // afterInvoke 不应抛异常，也不应写 Redis
        assertDoesNotThrow(() -> rail.afterInvoke(realCtxNoTool("session-null")));
    }

    // TC-16: Redis key 格式为 edpa:toolcount:{sessionId}
    @Test
    void redisKey_usesConstantPrefix() {
        when(hashOps.entries(anyString())).thenReturn(new HashMap<>());
        ActRuleConfig actrule = buildActRule(Map.of("call_versatile", 50));
        ExecutionLimitRail rail = new ExecutionLimitRail(actrule);

        rail.beforeInvoke(realCtxNoTool("key-test"));
        rail.beforeToolCall(realCtx("key-test", "call_versatile"));
        rail.afterInvoke(realCtxNoTool("key-test"));

        verify(hashOps).putAll(eq("edpa:toolcount:key-test"), anyMap());
        verify(redisTemplate).expire(eq("edpa:toolcount:key-test"), eq(Duration.ofSeconds(3600L)));
    }
}
