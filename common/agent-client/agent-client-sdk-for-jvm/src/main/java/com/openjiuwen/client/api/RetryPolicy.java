/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路异常后的 Task 查询、流式重订阅和幂等创建恢复策略。
 *
 * <p>失败序号从 1 开始。延迟按指数退避计算，并在应用 jitter 后受
 * {@link #maxDelay()} 限制。连续失败达到 {@link #maxConsecutiveFailures()}
 * 时停止当前 invocation 的本地观察，但不会取消服务端 Task。
 *
 * @since 2026-08-19
 */
public final class RetryPolicy {
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofMillis(800);
    private static final double DEFAULT_MULTIPLIER = 2.0d;
    private static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterFactor;
    private final int maxConsecutiveFailures;

    private RetryPolicy(Builder builder) {
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterFactor = builder.jitterFactor;
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
    }

    /**
     * 返回 FEAT-006 的兼容默认策略：3 次连续失败上限，200/400/800ms 退避，不启用 jitter。
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

    public Duration initialDelay() {
        return initialDelay;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    public double multiplier() {
        return multiplier;
    }

    public double jitterFactor() {
        return jitterFactor;
    }

    public int maxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    /**
     * 计算指定连续失败序号对应的延迟。
     *
     * @param consecutiveFailure 从 1 开始的连续失败序号
     * @return 退避延迟
     */
    public Duration delayForFailure(int consecutiveFailure) {
        if (consecutiveFailure < 1) {
            throw new IllegalArgumentException("consecutiveFailure must be at least 1");
        }
        double exponential = initialDelay.toMillis()
                * Math.pow(multiplier, consecutiveFailure - 1.0d);
        double bounded = Math.min(maxDelay.toMillis(), exponential);
        if (jitterFactor > 0.0d) {
            double jitter = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
            bounded *= 1.0d + jitter;
        }
        long delayMillis = Math.max(1L, Math.min(maxDelay.toMillis(), Math.round(bounded)));
        return Duration.ofMillis(delayMillis);
    }

    /**
     * RetryPolicy 构造器。
     */
    public static final class Builder {
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;
        private Duration maxDelay = DEFAULT_MAX_DELAY;
        private double multiplier = DEFAULT_MULTIPLIER;
        private double jitterFactor;
        private int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;

        public Builder initialDelay(Duration value) {
            this.initialDelay = requirePositiveMillis(value, "initialDelay");
            return this;
        }

        public Builder maxDelay(Duration value) {
            this.maxDelay = requirePositiveMillis(value, "maxDelay");
            return this;
        }

        public Builder multiplier(double value) {
            if (!Double.isFinite(value) || value < 1.0d) {
                throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
            }
            this.multiplier = value;
            return this;
        }

        public Builder jitterFactor(double value) {
            if (!Double.isFinite(value) || value < 0.0d || value >= 1.0d) {
                throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0)");
            }
            this.jitterFactor = value;
            return this;
        }

        public Builder maxConsecutiveFailures(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxConsecutiveFailures must be at least 1");
            }
            this.maxConsecutiveFailures = value;
            return this;
        }

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
