/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

/**
 * 调用模式（FEAT-006 §2.5）。决定 SDK 与网关之间使用的 A2A 方法与响应形态。
 *
 * <ul>
 * <li>{@link #STREAMING} —— 使用 A2A {@code SendStreamingMessage}（HTTP + SSE）增量消费事件。</li>
 * <li>{@link #BLOCKING} —— 使用 {@code SendMessage(returnImmediately=false)}；非终态响应进入有界状态观察。</li>
 * <li>{@link #ASYNC} —— 使用 {@code SendMessage(returnImmediately=true)}；受理后停止自动网络活动，
 * 由业务按需查询状态。</li>
 * </ul>
 *
 * @since 2026-07-27
 */
public enum InvocationMode {
    BLOCKING,
    STREAMING,
    ASYNC
}
