/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.state.spi;

import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;

import java.util.Optional;

/**
 * 客户端执行记录 outbox / ACK 状态存储（FEAT-007 §4）。
 *
 * <p>承担两类职责：
 * <ul>
 *   <li><b>执行记录 outbox</b>：以 {@code toolCallId} 为主键的"最多执行一次"幂等保证。
 *       {@link #saveRecordIfAbsent} 利用底层存储的原子写语义，使得同一 toolCallId
 *       即使被并发触发也只会写入并返回同一份记录。</li>
 *   <li><b>续传 ACK 簿记</b>：{@link #markSubmitted} / {@link #isSubmitted} 记录某次工具结果
 *       是否已成功续传给服务端，避免对同一 toolCallId 重复提交续跑请求。</li>
 * </ul>
 *
 * <p>默认实现为进程内内存版本（{@code InMemoryStateStore}）；可替换为持久化实现以支持跨重启恢复。
 * 实现需保证线程安全。
 */
public interface ClientStateStore {

    /** 查找已落库的执行记录（若已存在）。 */
    Optional<ToolExecutionRecord> findRecord(String toolCallId);

    /**
     * 原子写入执行记录：若该 {@code toolCallId} 尚无记录则写入 {@code record} 并返回它；
     * 若已存在则返回既有记录。调用方据此合流并发触发的同一次调用，保证"最多执行一次"。
     */
    ToolExecutionRecord saveRecordIfAbsent(String toolCallId, ToolExecutionRecord record);

    /** 标记某次工具结果已成功续传给服务端（此后只重投不重跑）。 */
    void markSubmitted(String toolCallId);

    /** 判断某次工具结果是否已成功续传过。 */
    boolean isSubmitted(String toolCallId);
}
