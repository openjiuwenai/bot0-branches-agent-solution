/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.a2a;

/** A2A 传输层的运行时异常（HTTP/协议错误统一包装）。
 * @since 2026-07-27
 */
public final class A2aTransportException extends RuntimeException {

    public A2aTransportException(String message) {
        super(message);
    }

    public A2aTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
