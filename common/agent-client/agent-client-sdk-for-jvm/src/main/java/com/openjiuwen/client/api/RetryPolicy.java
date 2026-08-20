/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 定义 agent-client 在链路异常场景下的恢复行为。
 *
 * <p>适用范围包括 Task 状态轮询、流式响应重订阅和幂等创建重试。
 * 从第 1 次失败开始计数，每次失败后等待一段退避时间再重试。退避时间
 * 从初始值起步，逐次放大，但不会超过上限。可选地引入随机抖动以分散
 * 重试风暴。当连续失败次数达到设定的上限后，本地停止观察，服务端
 * Task 不受影响。
 *
 * @since 2026-08-19
 */
public final class RetryPolicy {
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofMillis(800);
    private static final double DEFAULT_MULTIPLIER = 2.0d;
    private static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private final int maxConsecutiveFailures;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterFactor;

    private RetryPolicy(Builder builder) {
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterFactor = builder.jitterFactor;
    }

    /**
     * 返回 FEAT-006 的兼容默认策略：最多 3 次连续失败，
     * 退避间隔依次为 200ms、400ms、800ms，不启用抖动。
     *
     * @return 默认策略
     */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /**
     * 创建策略构造器。
     *
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回最大连续失败次数。
     *
     * @return 最大连续失败次数
     */
    public int maxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    /**
     * 返回初始退避延迟。
     *
     * @return 初始退避延迟
     */
    public Duration initialDelay() {
        return initialDelay;
    }

    /**
     * 返回最大退避延迟。
     *
     * @return 最大退避延迟
     */
    public Duration maxDelay() {
        return maxDelay;
    }

    /**
     * 返回退避倍数。
     *
     * @return 退避倍数
     */
    public double multiplier() {
        return multiplier;
    }

    /**
     * 返回抖动因子。
     *
     * @return 抖动因子
     */
    public double jitterFactor() {
        return jitterFactor;
    }

    /**
     * 计算指定连续失败序号对应的退避延迟。
     *
     * @param consecutiveFailure 从 1 开始的连续失败序号
     * @return 退避延迟
     */
    public Duration delayForFailure(int consecutiveFailure) {
        if (consecutiveFailure < 1) {
            throw new IllegalArgumentException("consecutiveFailure must be at least 1");
        }
        long baseMillis = scaleUpToCeiling(initialDelay.toMillis(), consecutiveFailure - 1);
        long jitteredMillis = applyJitterTo(baseMillis);
        long clampedMillis = clampToValidRange(jitteredMillis);
        return Duration.ofMillis(clampedMillis);
    }

    private long scaleUpToCeiling(long startMillis, int times) {
        long ceiling = maxDelay.toMillis();
        long value = startMillis;
        int remaining = times;
        while (remaining > 0 && value < ceiling) {
            long next = BigDecimal.valueOf(value)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
            value = Math.min(ceiling, next);
            remaining--;
        }
        return value;
    }

    private long applyJitterTo(long valueMillis) {
        if (jitterFactor <= 0.0d) {
            return valueMillis;
        }
        BigDecimal scaled = BigDecimal.valueOf(valueMillis);
        BigDecimal low = BigDecimal.ONE.subtract(BigDecimal.valueOf(jitterFactor));
        BigDecimal high = BigDecimal.ONE.add(BigDecimal.valueOf(jitterFactor));
        double randomFactor = ThreadLocalRandom.current()
                .nextDouble(low.doubleValue(), high.doubleValue());
        BigDecimal factor = BigDecimal.valueOf(randomFactor);
        return scaled.multiply(factor).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private long clampToValidRange(long valueMillis) {
        long bottom = 1L;
        long top = maxDelay.toMillis();
        if (valueMillis < bottom) {
            return bottom;
        }
        if (valueMillis > top) {
            return top;
        }
        return valueMillis;
    }

    /**
     * RetryPolicy 构造器。
     */
    public static final class Builder {
        private int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;
        private Duration maxDelay = DEFAULT_MAX_DELAY;
        private double multiplier = DEFAULT_MULTIPLIER;
        private double jitterFactor;

        /**
         * 设置最大连续失败次数。
         *
         * @param value 最大连续失败次数，至少为 1
         * @return 当前构造器
         */
        public Builder maxConsecutiveFailures(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxConsecutiveFailures must be at least 1");
            }
            this.maxConsecutiveFailures = value;
            return this;
        }

        /**
         * 设置初始退避延迟。
         *
         * @param value 初始延迟
         * @return 当前构造器
         */
        public Builder initialDelay(Duration value) {
            this.initialDelay = requirePositiveMillis(value, "initialDelay");
            return this;
        }

        /**
         * 设置最大退避延迟。
         *
         * @param value 最大延迟
         * @return 当前构造器
         */
        public Builder maxDelay(Duration value) {
            this.maxDelay = requirePositiveMillis(value, "maxDelay");
            return this;
        }

        /**
         * 设置退避倍数。
         *
         * @param value 退避倍数，至少为 1.0
         * @return 当前构造器
         */
        public Builder multiplier(double value) {
            if (!Double.isFinite(value) || value < 1.0d) {
                throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
            }
            this.multiplier = value;
            return this;
        }

        /**
         * 设置抖动因子。
         *
         * @param value 抖动因子，取值范围 [0.0, 1.0)
         * @return 当前构造器
         */
        public Builder jitterFactor(double value) {
            if (!Double.isFinite(value) || value < 0.0d || value >= 1.0d) {
                throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0)");
            }
            this.jitterFactor = value;
            return this;
        }

        /**
         * 构建 RetryPolicy 实例。
         *
         * @return RetryPolicy 实例
         */
        public RetryPolicy build() {
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
            }
            return new RetryPolicy(this);
        }

        private static Duration requirePositiveMillis(Duration value, String name) {
            Duration duration = Objects.requireNonNull(value, name);
            if (duration.isNegative() || duration.isZero() || duration.toMillis() < 1L) {
                throw new IllegalArgumentException(name + " must be at least 1ms");
            }
            return duration;
        }
    }
}
