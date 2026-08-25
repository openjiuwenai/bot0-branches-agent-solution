/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.observability;

import com.openjiuwen.agents.pev.observability.PevTrace;
/**
 * 分支轨迹——一个分支的地址 + 该分支的 {@link PevTrace}。
 *
 * <p>组合而非继承：不修改 PevTrace record（零破坏现有消费者）。
 * Forest 中的每个节点就是一个 BranchedTrace。
 *
 * @param address 分支地址（branchId + parent + forkPoint）
 * @param trace   本分支的执行轨迹
 * @since 2026-08
 */
public record BranchedTrace(BranchAddress address, PevTrace trace) {

    /**
     * 便捷取 branchId。
     *
     * @return 分支标识
     */
    public String branchId() {
        return address.branchId();
    }
}
