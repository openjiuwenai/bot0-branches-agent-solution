/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

/**
 * 服务端任务状态在客户端的投影（FEAT-006 §L-13、L2 Feat-Func-006 §3.3）。
 *
 * <p>重要边界：这是<b>投影</b>而非权威状态。权威 Task 状态由 runtime 拥有；
 * 客户端仅缓存最近一次观测到的状态，任何操作前应容忍其滞后并允许通过 {@code tasks/get} 校正。
 *
 * <p>与 A2A wire 的 {@code TaskState} 一一对应，额外增加 {@link #UNKNOWN} 表示"本地暂不可判定"。
 *
 * @since 2026-07-27
 */
public enum TaskState {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED,
    /**
     * 本地无法判定（如映射缺失、连接中断且未取得权威快照）。需通过 tasks/get 校正。
     */
    UNKNOWN;

    /**
     * 是否为终态（不会再自发变化）。
     *
     * @return 终态返回 true，否则 false
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }
}
