/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 工具暴露策略（FEAT-007 §L-02/03）。
 *
 * <p>默认不暴露任何本地工具。业务需按"最小暴露"原则显式授权：
 * 既可在会话级 {@code exposeInConversation} 设置默认，也可在调用级
 * {@code InvocationRequest.exposure} 覆盖/收窄。二者通过 {@link #and(ToolExposurePolicy)} 组合，
 * 结果对某工具暴露当且仅当两级都暴露它——保证调用级只能收紧、不能放大会话级授权。
 */
public final class ToolExposurePolicy {

    private final String label;
    private final Predicate<String> predicate;

    private ToolExposurePolicy(String label, Predicate<String> predicate) {
        this.label = label;
        this.predicate = predicate;
    }

    public boolean isExposed(String toolId) {
        return predicate.test(toolId);
    }

    public String label() {
        return label;
    }

    /** 组合两级策略：结果 = 两级取交集（都允许才暴露）。 */
    public ToolExposurePolicy and(ToolExposurePolicy other) {
        if (other == null) {
            return this;
        }
        return new ToolExposurePolicy(label + "&" + other.label,
                id -> this.predicate.test(id) && other.predicate.test(id));
    }

    /** 不暴露任何工具（默认）。 */
    public static ToolExposurePolicy none() {
        return new ToolExposurePolicy("none", id -> false);
    }

    /** 暴露全部已注册工具（谨慎使用）。 */
    public static ToolExposurePolicy all() {
        return new ToolExposurePolicy("all", id -> true);
    }

    /** 仅暴露白名单内的工具。 */
    public static ToolExposurePolicy allow(String... toolIds) {
        Set<String> set = new LinkedHashSet<>();
        for (String id : toolIds) {
            set.add(id);
        }
        return new ToolExposurePolicy("allow" + set, set::contains);
    }

    /** 暴露除黑名单外的全部工具。 */
    public static ToolExposurePolicy allExcept(String... toolIds) {
        Set<String> set = new LinkedHashSet<>();
        for (String id : toolIds) {
            set.add(id);
        }
        return new ToolExposurePolicy("allExcept" + set, id -> !set.contains(id));
    }
}
