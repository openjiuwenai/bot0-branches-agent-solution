/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全链路轨迹（第二批）配置（{@code openjiuwen.service.trajectory.link.*}）。总开关为
 * {@code enabled}，默认 false，关闭时宿主零行为变化；不设 OTEL_* 式环境变量回退，
 * 环境变量仅经 Spring 宽松绑定自然生效。
 *
 * @since 2026-08-26
 */
@ConfigurationProperties(prefix = "openjiuwen.service.trajectory.link")
public class TrajectoryLinkProperties {
    /** 默认记录 TTL（秒）：7 天（对齐 L2 §6.1 示例）——须不短于 carrier TTL，否则重启恢复/轮次恢复被默认值架空。 */
    public static final long DEFAULT_TTL_SECONDS = 604800L;

    /** carrier 条目惰性清理默认 TTL（秒）：24h。 */
    public static final long DEFAULT_CARRIER_TTL_SECONDS = 86400L;

    /**
     * 总开关；装配条件直接消费此属性（@ConditionalOnProperty），默认 false。
     */
    private boolean enabled;

    /**
     * 执行树/审计记录 key 的 TTL（秒）；不配置时沿用 checkpointer TTL 口径（3600）。
     * 索引 key 的 TTL 不短于记录 key。
     */
    private Long ttlSeconds;

    /**
     * 异步写入队列背压上限（满则丢弃并 WARN，不占执行线程），默认 10000。
     */
    private int queueCapacity = 10000;

    /**
     * 单后台线程批量刷写间隔（毫秒），默认 1000。
     */
    private int flushIntervalMs = 1000;

    /**
     * TraceContextCarrier 条目惰性清理 TTL（秒），默认 86400（24h）。
     */
    private long carrierTtlSeconds = DEFAULT_CARRIER_TTL_SECONDS;

    /**
     * Returns whether the link capability is enabled.
     *
     * @return true when enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the configured record TTL in seconds.
     *
     * @return ttl seconds, or null to use the default
     */
    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * Returns the async write queue capacity.
     *
     * @return queue capacity
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * Returns the flush interval in milliseconds.
     *
     * @return flush interval ms
     */
    public int getFlushIntervalMs() {
        return flushIntervalMs;
    }

    /**
     * Returns the carrier entry TTL in seconds.
     *
     * @return carrier ttl seconds
     */
    public long getCarrierTtlSeconds() {
        return carrierTtlSeconds;
    }

    /**
     * Returns the effective record TTL (configured or default).
     *
     * @return effective ttl seconds
     */
    public long effectiveTtlSeconds() {
        return ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    /**
     * Sets enabled.
     *
     * @param enabled enabled flag
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Sets the record TTL in seconds.
     *
     * @param ttlSeconds ttl seconds
     */
    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Sets the queue capacity.
     *
     * @param queueCapacity queue capacity
     */
    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    /**
     * Sets the flush interval in milliseconds.
     *
     * @param flushIntervalMs flush interval ms
     */
    public void setFlushIntervalMs(int flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * Sets the carrier entry TTL in seconds.
     *
     * @param carrierTtlSeconds carrier ttl seconds
     */
    public void setCarrierTtlSeconds(long carrierTtlSeconds) {
        this.carrierTtlSeconds = carrierTtlSeconds;
    }
}
