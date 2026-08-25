/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;

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
    public boolean retryUnconfirmedCreate() {
        return true;
    }
}
