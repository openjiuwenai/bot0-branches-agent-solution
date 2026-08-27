/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

/**
 * 传输层原始帧旁路观察口。
 *
 * <p>用于消费底层 wire 报文原文，默认不启用；实现必须是非阻塞、best-effort，
 * 不得影响主事件流投递。
 *
 * <p>这是早期字符串接口的兼容入口。新代码应使用 {@link RawResponseObserver}，
 * 以获得 HTTP 状态、响应头、完整 SSE event block 和重放标识。
 *
 * @since 2026-08-25
 */
@FunctionalInterface
@Deprecated(forRemoval = false)
public interface RawFrameConsumer {
    /**
     * 接收一条原始 wire 帧。
     *
     * @param invocationRef 客户端调用句柄
     * @param sessionId 会话标识
     * @param rawFrame 旧版兼容载荷，可能是 SSE event block，也可能是 unary/GetTask 的完整响应体
     * @param source 来源标记，如 CREATE_UNARY / GET_TASK / SUBSCRIBE
     */
    void accept(String invocationRef, String sessionId, String rawFrame, String source);

    /**
     * 默认空实现。
     *
     * @return 空消费器
     */
    static RawFrameConsumer noop() {
        return (invocationRef, sessionId, rawFrame, source) -> {
            // no-op
        };
    }
}
