/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

/**
 * 调用模式（FEAT-006 §2.5）。决定 SDK 与网关之间使用的 A2A 方法与响应形态。
 *
 * <p>本迭代（730）只交付 {@link #STREAMING}；{@link #BLOCKING} / {@link #ASYNC} 为预留枚举，
 * 传入时 {@code invoke()} 会以 {@code UNSUPPORTED_MODE} 拒绝（交付面即能力面）。
 *
 * <ul>
 * <li>{@link #STREAMING} —— 增量事件流，wire 上用 A2A {@code SendStreamingMessage}（HTTP + SSE）。<b>已交付</b>。</li>
 * <li>{@link #BLOCKING} —— 语义上"一次拿到最终结果"，wire 上拟用 {@code SendMessage}（单条 JSON）。<b>预留、未交付</b>。</li>
 * <li>{@link #ASYNC} —— 提交即返回句柄，随后按需拉取状态。<b>预留、未交付</b>。</li>
 * </ul>
 *
 * @since 2026-07-27
 */
public enum InvocationMode {
    BLOCKING,
    STREAMING,
    ASYNC
}
