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

package com.huawei.ascend.edp.rail;

import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.RedisConfig;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行次数限制 Rail。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>在工具调用前统计每个工具的调用次数。</li>
 *     <li>读取 actrule.yaml 中 tool_limits 的单工具调用上限。</li>
 *     <li>当工具调用次数超过限制时请求强制结束，避免工具循环失控。</li>
 *     <li>会话开始时从 Redis 恢复计数，会话结束时持久化到 Redis。</li>
 *     <li>定期清理僵尸会话 entry，防止内存泄漏。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #ExecutionLimitRail(ActRuleConfig)}：创建工具执行限制 Rail。</li>
 *     <li>{@link #beforeInvoke(AgentCallbackContext)}：会话开始，从 Redis 恢复计数。</li>
 *     <li>{@link #beforeToolCall(AgentCallbackContext)}：工具调用前回调入口。</li>
 *     <li>{@link #afterInvoke(AgentCallbackContext)}：会话结束，持久化到 Redis。</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public class ExecutionLimitRail extends AgentRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionLimitRail.class);
    private static final int CLEANUP_INTERVAL = 100;
    private static final long STALE_THRESHOLD_MS = 3600000L;

    private final Set<String> alreadyWarned = ConcurrentHashMap.newKeySet();

    /**
     * 行为治理配置，提供 tool_limits 工具调用次数上限。
     */

    private final ActRuleConfig actrule;

    /**
     * Redis key 前缀与 TTL（常量，工具调用计数持久化到 edpa:toolcount:{sessionId}）。
     */

    private static final String REDIS_KEY_PREFIX = "edpa";
    private static final long TOOL_COUNT_TTL_SECONDS = 3600L;

    /**
     * 本地计数器：sessionId -> (toolName -> count)。
     * beforeInvoke 从 Redis 恢复，beforeToolCall 本地递增，afterInvoke 持久化到 Redis。
     */

    private final Map<String, Map<String, Integer>> toolCallCounts = new ConcurrentHashMap<>();

    /**
     * 会话最后活跃时间：sessionId -> timestamp，用于定期清理僵尸 entry。
     */

    private final Map<String, Long> sessionLastActive = new ConcurrentHashMap<>();

    /**
     * 调用计数器，每 CLEANUP_INTERVAL 次触发一次清理。
     */

    private final AtomicInteger callCounter = new AtomicInteger(0);

    /**
     * 构造工具执行限制 Rail。
     *
     * @param actrule 行为治理配置
     */

    public ExecutionLimitRail(ActRuleConfig actrule) {
        this.actrule = actrule;
        setPriority(70);
    }

    /**
     * 会话开始回调：从 Redis 恢复计数到本地。
     *
     * @param ctx OpenJiuwen 回调上下文
     */

    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        String sid = resolveSid(ctx);

        // 防御性清理：移除可能残留的旧 entry
        toolCallCounts.remove(sid);
        sessionLastActive.remove(sid);

        StringRedisTemplate redisTemplate = RedisConfig.getStringRedisTemplate();
        if (redisTemplate == null) {
            toolCallCounts.put(sid, new ConcurrentHashMap<>());
            sessionLastActive.put(sid, System.currentTimeMillis());
            return;
        }

        String key = redisKey(sid);
        try {
            Map<Object, Object> recovered = redisTemplate.opsForHash().entries(key);
            if (recovered.isEmpty()) {
                LOGGER.warn("[ExecutionLimitRail] session {} counter not found in Redis, starting from 0", sid);
                toolCallCounts.put(sid, new ConcurrentHashMap<>());
            } else {
                Map<String, Integer> local = new ConcurrentHashMap<>();
                recovered.forEach((k, v) ->
                        local.put(String.valueOf(k), Integer.parseInt(String.valueOf(v))));
                toolCallCounts.put(sid, local);
                LOGGER.info("[ExecutionLimitRail] session {} recovered from Redis: key={}, counts={}",
                        sid, key, local);
            }
        } catch (Exception e) {
            LOGGER.error("[ExecutionLimitRail] session {} Redis GET failed, starting from 0", sid, e);
            toolCallCounts.put(sid, new ConcurrentHashMap<>());
        }

        sessionLastActive.put(sid, System.currentTimeMillis());
    }

    /**
     * 工具调用前回调。
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用信息
     */

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        int limit = getToolLimit(toolName);
        String sid = resolveSid(ctx);

        Map<String, Integer> sessionCounts = toolCallCounts.computeIfAbsent(sid, k -> new ConcurrentHashMap<>());
        int currentCount = sessionCounts.merge(toolName, 1, Integer::sum);
        sessionLastActive.put(sid, System.currentTimeMillis());

        if (callCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            cleanupStaleEntries();
        }

        if (currentCount > limit) {
            LOGGER.info("ExecutionLimitRail: tool {} call count {} > limit {}, forcing finish", toolName, currentCount,
                    limit);
            ctx.requestForceFinish(Map.of("message", "工具调用次数已达上限"));
        }
    }

    /**
     * 会话结束回调：持久化计数到 Redis 并刷新 TTL。
     *
     * @param ctx OpenJiuwen 回调上下文
     */

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        String sid = resolveSid(ctx);
        Map<String, Integer> counts = toolCallCounts.remove(sid);
        sessionLastActive.remove(sid);

        if (counts == null || counts.isEmpty()) {
            return;
        }

        StringRedisTemplate redisTemplate = RedisConfig.getStringRedisTemplate();
        if (redisTemplate == null) {
            return;
        }

        String key = redisKey(sid);
        try {
            Map<String, String> hashEntries = new HashMap<>();
            counts.forEach((tool, count) -> hashEntries.put(tool, String.valueOf(count)));
            redisTemplate.opsForHash().putAll(key, hashEntries);
            redisTemplate.expire(key, Duration.ofSeconds(TOOL_COUNT_TTL_SECONDS));
            LOGGER.debug("[ExecutionLimitRail] session {} persisted {} tool counts, TTL={}s",
                    sid, counts.size(), TOOL_COUNT_TTL_SECONDS);
        } catch (Exception e) {
            LOGGER.error("[ExecutionLimitRail] session {} failed to persist tool counts to Redis", sid, e);
        }
    }

    /**
     * 定期清理僵尸 entry：移除超过 1 小时未活跃的会话。
     */

    private void cleanupStaleEntries() {
        long threshold = System.currentTimeMillis() - STALE_THRESHOLD_MS;
        int removed = 0;
        for (String sid : sessionLastActive.keySet()) {
            Long lastActive = sessionLastActive.get(sid);
            if (lastActive != null && lastActive < threshold) {
                toolCallCounts.remove(sid);
                sessionLastActive.remove(sid);
                removed++;
                LOGGER.warn("[ExecutionLimitRail] removed stale session: sid={}, lastActive={}", sid, lastActive);
            }
        }
        if (removed > 0) {
            LOGGER.debug("[ExecutionLimitRail] cleanup done, removed {} stale sessions, active={}",
                    removed, sessionLastActive.size());
        }
    }

    private String resolveSid(AgentCallbackContext ctx) {
        return ctx.getSession() != null ? ctx.getSession().getSessionId() : "_default";
    }

    private String redisKey(String sid) {
        return REDIS_KEY_PREFIX + ":toolcount:" + sid;
    }

    /**
     * 获取指定工具的调用次数上限。
     *
     * @param toolName 工具名
     * @return 工具调用上限；未配置时返回默认值 100
     */

    private int getToolLimit(String toolName) {
        if (actrule != null && actrule.getToolLimits() != null) {
            Integer limit = actrule.getToolLimits().get(toolName);
            if (limit != null) {
                LOGGER.debug("[ExecutionLimitRail] tool '{}' limit from config: {}", toolName, limit);
                return limit;
            }
        }

        if (alreadyWarned.add(toolName)) {
            LOGGER.info(
                    "[ExecutionLimitRail] tool '{}' not configured in tool_limits, "
                            + "using default limit=100 (first occurrence)",
                    toolName);
        }
        return 100;
    }
}
