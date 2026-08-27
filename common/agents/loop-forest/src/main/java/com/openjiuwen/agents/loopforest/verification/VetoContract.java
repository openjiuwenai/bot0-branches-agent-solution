/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Veto 写入契约——定义每个写入工具允许的顶层字段集合（白名单）。
 *
 * <p>GLH-2 Python 实验验证的核心机制（bait 根除 0% 双模型一致 + SAT 不伤）的 Java 移植。
 * 契约由宿主在装配时提供（构造注入），不依赖外部文件——白名单本身是配置而非 prompt。
 *
 * <p><b>零提及纪律</b>：契约只用于 {@link #shouldVeto} 的判定，
 * 拒绝消息永远不点名哪个字段多余（提示即诱导的反面教训——提及本身就是诱导）。
 *
 * <p>不可变；线程安全。
 *
 * @since 2026-08
 */
public final class VetoContract {

    /** 工具名 → 允许的顶层字段名集合（白名单）。 */
    private final Map<String, Set<String>> allowedFieldsByTool;

    /**
     * 构造契约。
     *
     * @param allowedFieldsByTool 工具名到允许字段集合的映射；内部做防御性拷贝
     */
    public VetoContract(Map<String, Set<String>> allowedFieldsByTool) {
        this.allowedFieldsByTool = allowedFieldsByTool == null
                ? Collections.emptyMap()
                : Map.copyOf(allowedFieldsByTool);
    }

    /**
     * 判定一次写入是否应被否决。
     *
     * @param toolName 工具名
     * @param topLevelKeys 写入内容的顶层键集合
     * @return true 如果存在白名单之外的键（应否决）
     */
    public boolean shouldVeto(String toolName, Set<String> topLevelKeys) {
        Set<String> allowed = allowedFieldsByTool.get(toolName);
        if (allowed == null || allowed.isEmpty()) {
            return false; // 无契约的工具 fail-open（诚实边界）
        }
        return !allowed.containsAll(topLevelKeys);
    }

    /**
     * 获取指定工具的白名单（只读视图）。
     *
     * @param toolName 工具名
     * @return 白名单集合；无契约返回空集
     */
    public Set<String> allowedFields(String toolName) {
        return allowedFieldsByTool.getOrDefault(toolName, Collections.emptySet());
    }

    /**
     * 是否覆盖指定工具（有非空白名单）。
     *
     * @param toolName 工具名
     * @return true 如果该工具有契约
     */
    public boolean covers(String toolName) {
        Set<String> allowed = allowedFieldsByTool.get(toolName);
        return allowed != null && !allowed.isEmpty();
    }

    /**
     * 契约是否为空（无任何工具带非空白名单）。
     *
     * @return true 如果没有工具被契约覆盖
     */
    public boolean isEmpty() {
        return allowedFieldsByTool.values().stream()
                .noneMatch(s -> !s.isEmpty());
    }
}
