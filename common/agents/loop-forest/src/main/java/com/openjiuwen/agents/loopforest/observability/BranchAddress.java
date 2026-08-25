/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.observability;

/**
 * 分支地址——graph-loop 树结构轨迹的寻址单元（S1/S2 承载位）。
 *
 * <p>每个分支有一个唯一 {@code branchId} 和可选的 {@code parentBranchId}（根分支为 null）。
 * {@code forkPhaseIndex} 指向父分支 trace 中的分叉点（第几个 Phase 处分叉），
 * 用于回滚时定位「回到哪里」。
 *
 * <p><b>设计决策</b>：不可变 record，作为 {@link BranchedTrace} 的组成部分。
 * 不直接修改 {@link PevTrace}（已有消费者零破坏）。
 *
 * <p><b>GLH-2 S1/S2 规格映射</b>：
 * <ul>
 *   <li>S1（签名=轨迹一等公民）：branchId 是分支的签名标识</li>
 *   <li>S2（回滚锚+父签名）：parentBranchId + forkPhaseIndex 构成回滚寻址</li>
 * </ul>
 *
 * @param branchId       本分支唯一标识（非空）
 * @param parentBranchId 父分支标识（根分支为 null）
 * @param forkPhaseIndex 父分支 trace 中的分叉点 Phase 索引（根分支为 -1）
 * @since 2026-08
 */
public record BranchAddress(String branchId, String parentBranchId, int forkPhaseIndex) {

    /**
     * 根分支构造器（无父分支）。
     *
     * @param branchId 分支标识
     * @return 根分支地址
     */
    public static BranchAddress root(String branchId) {
        return new BranchAddress(branchId, null, -1);
    }

    /**
     * 子分支构造器。
     *
     * @param branchId 本分支标识
     * @param parentBranchId 父分支标识
     * @param forkPhaseIndex 父 trace 中的分叉 Phase 索引（≥0）
     * @return 子分支地址
     */
    public static BranchAddress fork(String branchId, String parentBranchId, int forkPhaseIndex) {
        if (parentBranchId == null || parentBranchId.isBlank()) {
            throw new IllegalArgumentException(
                    "fork branch requires parentBranchId (use BranchAddress.root for root)");
        }
        if (forkPhaseIndex < 0) {
            throw new IllegalArgumentException(
                    "forkPhaseIndex must be >= 0 for fork branch: " + forkPhaseIndex);
        }
        return new BranchAddress(branchId, parentBranchId, forkPhaseIndex);
    }

    /**
     * 是否为根分支（无父）。
     *
     * @return true 如果无父分支
     */
    public boolean isRoot() {
        return parentBranchId == null;
    }
}
