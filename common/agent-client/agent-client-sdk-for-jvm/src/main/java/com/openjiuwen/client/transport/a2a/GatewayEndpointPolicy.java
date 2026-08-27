/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.ErrorCodes;
import com.openjiuwen.client.transport.spi.TransportProvider;

/** Gateway 请求策略，保持现有鉴权、路由和创建幂等语义。 */
final class GatewayEndpointPolicy implements EndpointPolicy {
    static final GatewayEndpointPolicy INSTANCE = new GatewayEndpointPolicy();

    private GatewayEndpointPolicy() {
    }

    @Override
    public EndpointType type() {
        return EndpointType.GATEWAY;
    }

    @Override
    public TransportProvider.CreateCommand createCommand(TransportProvider.CreateCommand command) {
        if (command.agentId() == null || command.agentId().isBlank()) {
            // 自定义 Transport 门面或错误地把 runtimeBuilder 请求交给 Gateway Client，仍可能绕过
            // gatewayBuilder 的 API 形状约束；必须在编码/发网前保留纵深校验。
            throw new IllegalArgumentException(ErrorCodes.VALIDATION_AGENT_ID
                    + ": agentId must be non-blank for a Gateway invocation");
        }
        return command;
    }

    @Override
    public boolean retryUnconfirmedCreate() {
        return true;
    }
}
