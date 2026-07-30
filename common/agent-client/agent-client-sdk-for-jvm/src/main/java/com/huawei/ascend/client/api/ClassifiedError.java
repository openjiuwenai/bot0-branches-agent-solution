/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 携带稳定错误码与可重试判定的异常约定（FEAT-006「错误分类」MUST）。
 *
 * <p>由传输实现的异常类型实现本接口。SDK 内核据此把传输故障归一化为
 * {@link InvocationEvent.Failed} 的 {@code errorCode} 与 {@code retryable}，
 * 从而<b>不依赖</b>任何具体协议实现包，也不靠异常消息做字符串匹配。
 *
 * @since 2026-07-30
 */
public interface ClassifiedError {
    /**
     * 稳定错误码，取值见 {@link ErrorCodes}。
     *
     * @return 稳定错误码
     */
    String code();

    /**
     * 是否可退避重试。
     *
     * @return 可重试返回 true
     */
    boolean retryable();

    /**
     * 剥掉 {@link CompletionException} / {@link ExecutionException} 包装，取出已分类异常。
     *
     * @param t 原始异常，允许为 null
     * @return 已分类异常；无法识别时返回 null
     */
    static ClassifiedError unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ClassifiedError ce) {
                return ce;
            }
            if (!(cur instanceof CompletionException || cur instanceof ExecutionException)) {
                return null;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /**
     * 取异常的稳定错误码；无法识别时回退为 {@link ErrorCodes#NETWORK_ERROR}。
     *
     * <p>回退取网络错误而非 {@code AGENT_ERROR}：未分类的传输层异常本质是"没能和服务端完成一次交互"，
     * 把它说成服务端 Task 失败会误导调用方。
     *
     * @param t 原始异常
     * @return 稳定错误码
     */
    static String codeOf(Throwable t) {
        ClassifiedError ce = unwrap(t);
        return (ce != null) ? ce.code() : ErrorCodes.NETWORK_ERROR;
    }

    /**
     * 取异常的可重试性；无法识别时按错误码闭集判定。
     *
     * @param t 原始异常
     * @return 可重试返回 true
     */
    static boolean retryableOf(Throwable t) {
        ClassifiedError ce = unwrap(t);
        return (ce != null) ? ce.retryable() : ErrorCodes.isRetryable(codeOf(t));
    }
}
