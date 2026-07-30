/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.tool.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 工具暴露策略（FEAT-007 §L-02/03）。
 *
 * <p>默认不暴露任何本地工具。业务需按"最小暴露"原则显式授权：
 * 既可在会话级 {@code exposeInConversation} 设置默认，也可在调用级
 * {@code InvocationRequest.exposure} 覆盖/收窄。二者通过 {@link #and(ToolExposurePolicy)} 组合，
 * 结果对某工具暴露当且仅当两级都暴露它——保证调用级只能收紧、不能放大会话级授权。
 *
 * @since 2026-07-27
 */
public final class ToolExposurePolicy {
    private final String label;
    private final Predicate<String> predicate;

    /** 暴露窗口截止时刻；null 表示不设过期。 */
    private final Instant expiresAt;

    private ToolExposurePolicy(String label, Predicate<String> predicate) {
        this(label, predicate, null);
    }

    private ToolExposurePolicy(String label, Predicate<String> predicate, Instant expiresAt) {
        this.label = label;
        this.predicate = predicate;
        this.expiresAt = expiresAt;
    }

    /**
     * 派生一个带暴露窗口的策略：窗口关闭后，服务端再请求端侧工具将被结构化拒绝而<b>不执行</b>。
     *
     * <p>用于「本次授权只在一段时间内有效」的最小暴露场景（FEAT-007 §3 暴露策略过期时间）。
     *
     * @param ttl 自此刻起的有效时长
     * @return 带过期时刻的新策略
     */
    public ToolExposurePolicy expiringIn(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        return new ToolExposurePolicy(label + "@ttl" + ttl, predicate, Instant.now().plus(ttl));
    }

    /**
     * 派生一个在指定时刻过期的策略。
     *
     * @param instant 截止时刻
     * @return 带过期时刻的新策略
     */
    public ToolExposurePolicy expiringAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return new ToolExposurePolicy(label + "@until" + instant, predicate, instant);
    }

    /**
     * 暴露窗口截止时刻。
     *
     * @return 截止时刻；未设置过期时为空
     */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /**
     * 暴露窗口是否已关闭。
     *
     * @return 已过期返回 true；未设置过期恒为 false
     */
    public boolean isExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    /**
     * 判断指定工具是否暴露。
     *
     * @param toolId 工具标识
     * @return 暴露返回 true
     */
    public boolean isExposed(String toolId) {
        return predicate.test(toolId);
    }

    /**
     * 策略标签（用于日志/诊断）。
     *
     * @return 策略标签
     */
    public String label() {
        return label;
    }

    /**
     * 组合两级策略：结果 = 两级取交集（都允许才暴露）。
     *
     * @param other 另一个策略
     * @return 组合后的策略
     */
    public ToolExposurePolicy and(ToolExposurePolicy other) {
        if (other == null) {
            return this;
        }
        // 过期时刻取两者中更早的：与"只能收紧不能放大"一致，任一级窗口关闭即整体关闭。
        Instant merged;
        if (this.expiresAt == null) {
            merged = other.expiresAt;
        } else if (other.expiresAt == null) {
            merged = this.expiresAt;
        } else {
            merged = this.expiresAt.isBefore(other.expiresAt) ? this.expiresAt : other.expiresAt;
        }
        return new ToolExposurePolicy(label + "&" + other.label,
                id -> this.predicate.test(id) && other.predicate.test(id), merged);
    }

    /**
     * 不暴露任何工具（默认）。
     *
     * @return 不暴露任何工具（默认）。
     */
    public static ToolExposurePolicy none() {
        return new ToolExposurePolicy("none", id -> false);
    }

    /**
     * 暴露全部已注册工具（谨慎使用）。
     *
     * @return 暴露全部已注册工具（谨慎使用）。
     */
    public static ToolExposurePolicy all() {
        return new ToolExposurePolicy("all", id -> true);
    }

    /**
     * 仅暴露白名单内的工具。
     *
     * @param toolIds 工具标识集合
     * @return 仅暴露白名单内的工具。
     */
    public static ToolExposurePolicy allow(String... toolIds) {
        Set<String> set = new LinkedHashSet<>();
        for (String id : toolIds) {
            set.add(id);
        }
        return new ToolExposurePolicy("allow" + set, set::contains);
    }

    /**
     * 暴露除黑名单外的全部工具。
     *
     * @param toolIds 工具标识集合
     * @return 暴露除黑名单外的全部工具。
     */
    public static ToolExposurePolicy allExcept(String... toolIds) {
        Set<String> set = new LinkedHashSet<>();
        for (String id : toolIds) {
            set.add(id);
        }
        return new ToolExposurePolicy("allExcept" + set, id -> !set.contains(id));
    }
}
