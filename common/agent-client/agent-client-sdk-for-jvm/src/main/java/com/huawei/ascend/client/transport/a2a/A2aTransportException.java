/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.a2a;

/**
 * A2A 传输层的运行时异常（HTTP/协议错误统一包装）。
 *
 * @since 2026-07-27
 */
public final class A2aTransportException extends RuntimeException {
    /**
     * 构造异常。
     *
     * @param message 异常信息
     */
    public A2aTransportException(String message) {
        super(message);
    }

    /**
     * 构造异常（带原因）。
     *
     * @param message 异常信息
     * @param cause 原始异常
     */
    public A2aTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
