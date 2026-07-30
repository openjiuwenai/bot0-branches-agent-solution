/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.ErrorCodes;

/**
 * A2A 传输层的运行时异常，携带<b>稳定错误码与可重试判定</b>（FEAT-006「错误分类」MUST）。
 *
 * <p>不再把所有非 2xx 折叠为同一种失败：调用方可据 {@link #code()} 与 {@link #retryable()}
 * 区分「鉴权失败/参数非法/幂等冲突/限流/网络失败/服务端 Task 失败」。
 * 尤其是两种 409 幂等冲突状态码相同而可重试性相反，只能靠 code 区分（见 {@link ErrorCodes}）。
 *
 * @since 2026-07-27
 */
public final class A2aTransportException extends RuntimeException
        implements com.openjiuwen.client.api.ClassifiedError {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int httpStatus;
    private final boolean retryable;

    /**
     * 构造异常（未分类，按不可重试处理）。
     *
     * @param message 异常信息
     */
    public A2aTransportException(String message) {
        this(message, null, ErrorCodes.AGENT_ERROR, 0, false);
    }

    /**
     * 构造异常（带原因，未分类）。
     *
     * @param message 异常信息
     * @param cause 原始异常
     */
    public A2aTransportException(String message, Throwable cause) {
        this(message, cause, ErrorCodes.AGENT_ERROR, 0, false);
    }

    /**
     * 构造已分类的异常。
     *
     * @param message 异常信息
     * @param cause 原始异常，可为 null
     * @param code 稳定错误码，见 {@link ErrorCodes}
     * @param httpStatus HTTP 状态码；非 HTTP 来源传 0
     * @param retryable 是否可退避重试
     */
    public A2aTransportException(String message, Throwable cause, String code,
                                int httpStatus, boolean retryable) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    /**
     * 按 HTTP 治理错误构造：错误码优先取网关返回的 {@code code}，可重试性由错误码闭集判定。
     *
     * @param message 异常信息
     * @param code 网关返回的稳定错误码；为空时按状态码兜底推断
     * @param httpStatus HTTP 状态码
     * @return 已分类的传输异常
     */
    public static A2aTransportException governance(String message, String code, int httpStatus) {
        String resolved = (code != null && !code.isBlank()) ? code : ErrorCodes.fromHttpStatus(httpStatus);
        return new A2aTransportException(message, null, resolved, httpStatus,
                ErrorCodes.isRetryable(resolved));
    }

    /**
     * 按网络失败构造：连接/读超时、连接重置等，一律可重试。
     *
     * @param message 异常信息
     * @param cause 原始异常
     * @return 已分类的传输异常
     */
    public static A2aTransportException network(String message, Throwable cause) {
        return new A2aTransportException(message, cause, ErrorCodes.NETWORK_ERROR, 0, true);
    }

    /**
     * 稳定错误码。
     *
     * @return 稳定错误码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * HTTP 状态码；非 HTTP 来源为 0。
     *
     * @return HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 是否可退避重试。
     *
     * @return 可重试返回 true
     */
    @Override
    public boolean retryable() {
        return retryable;
    }
}
