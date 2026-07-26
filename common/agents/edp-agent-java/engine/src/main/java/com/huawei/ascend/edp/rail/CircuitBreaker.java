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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量级熔断器，无外部依赖。
 *
 * <p>状态机：</p>
 * <ul>
 *     <li>CLOSED：正常放行，记录连续失败次数。达到 failureThreshold 后切换到 OPEN。</li>
 *     <li>OPEN：快速失败，拒绝所有请求。经过 resetTimeout 后切换到 HALF_OPEN。</li>
 *     <li>HALF_OPEN：仅放行第一个请求（试探）。成功则切换回 CLOSED，失败则回到 OPEN。</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public class CircuitBreaker {
    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final int failureThreshold;
    private final Duration resetTimeout;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt = Instant.EPOCH;

    /**
     * 构造熔断器。
     *
     * @param name 熔断器名称（用于日志标识）
     * @param failureThreshold 连续失败次数阈值，达到后熔断器打开
     * @param resetTimeoutMs 熔断打开后经过此时间进入半开状态（毫秒）
     */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.resetTimeout = Duration.ofMillis(resetTimeoutMs);
    }

    /**
     * 熔断器状态枚举。
     * <ul>
     *     <li>CLOSED：正常放行</li>
     *     <li>OPEN：快速失败，拒绝所有请求</li>
     *     <li>HALF_OPEN：半开，仅放行一个试探请求</li>
     * </ul>
     */
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    /**
     * 检查是否允许请求通过。
     *
     * <p>CLOSED 状态始终放行；OPEN 状态在 resetTimeout 到期后转为 HALF_OPEN 并放行一个试探请求；
     * HALF_OPEN 状态拒绝除试探请求外的所有请求。</p>
     *
     * @return true 表示允许通过，false 表示熔断中应走降级逻辑
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (Duration.between(openedAt, Instant.now()).compareTo(resetTimeout) >= 0) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    LOGGER.info("[CircuitBreaker:{}] OPEN -> HALF_OPEN, allowing trial request", name);
                    return true;
                }
                return false;
            }
            return false;
        }
        // HALF_OPEN：仅允许一个试探请求（CAS 成功的那个）
        return false;
    }

    /**
     * 记录成功。重置失败计数，HALF_OPEN -> CLOSED。
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            LOGGER.info("[CircuitBreaker:{}] {} -> CLOSED (recovered)", name, prev);
        }
    }

    /**
     * 记录失败。递增失败计数，达到阈值则打开熔断器。
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt = Instant.now();
                LOGGER.warn("[CircuitBreaker:{}] CLOSED -> OPEN (consecutive failures={})", name, failures);
            } else if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = Instant.now();
                LOGGER.warn("[CircuitBreaker:{}] HALF_OPEN -> OPEN (trial request failed)", name);
            } else {
                LOGGER.debug("[CircuitBreaker:{}] already OPEN, failures={}", name, failures);
            }
        }
    }

    /**
     * 获取当前状态（主要用于日志和监控）。
     *
     * @return the state
     */
    public State getState() {
        return state.get();
    }

    /**
     * 获取连续失败次数。
     *
     * @return the consecutive failures
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
