/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.transport.spi.TransportProvider;

import java.util.Optional;

/** A2A Endpoint 间唯一允许变化的请求与恢复策略。 */
interface EndpointPolicy {
    /** 返回端点类型。 */
    EndpointType type();

    /** 返回凭据，若不需要则返回空。 */
    default Optional<String> credential(String supplied) {
        return Optional.ofNullable(supplied);
    }

    default TransportProvider.CreateCommand createCommand(TransportProvider.CreateCommand command) {
        return command;
    }

    /** 未确认的创建请求是否可重试。 */
    boolean retryUnconfirmedCreate();

    default boolean useSubscriptionForRecovery(InvocationMode mode) {
        return true;
    }

    default boolean cursorReplaySupported() {
        return true;
    }
}
