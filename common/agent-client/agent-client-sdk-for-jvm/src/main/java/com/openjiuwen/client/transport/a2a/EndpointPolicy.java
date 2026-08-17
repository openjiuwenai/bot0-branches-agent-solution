/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.transport.spi.TransportProvider;

/** A2A Endpoint 间唯一允许变化的请求与恢复策略。 */
interface EndpointPolicy {
    /**
     * 返回端点类型。
     *
     * @return 端点类型
     */
    EndpointType type();

    /**
     * 返回凭证。
     *
     * @param supplied 提供的凭证
     * @return 有效凭证
     */
    default String credential(String supplied) {
        return supplied;
    }

    /**
     * 创建命令。
     *
     * @param command 原始命令
     * @return 处理后的命令
     */
    default TransportProvider.CreateCommand createCommand(TransportProvider.CreateCommand command) {
        return command;
    }

    /**
     * 是否重试未确认的创建请求。
     *
     * @return 是否重试
     */

    boolean retryUnconfirmedCreate();

    default boolean useSubscriptionForRecovery(InvocationMode mode) {
        return true;
    }

    default boolean cursorReplaySupported() {
        return true;
    }
}
