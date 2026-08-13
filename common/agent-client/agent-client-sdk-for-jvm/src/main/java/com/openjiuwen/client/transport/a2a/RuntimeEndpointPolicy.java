/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.transport.spi.TransportProvider;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runtime 直连策略：使用 metadata allowlist，且不发送 Gateway 身份信息。 */
final class RuntimeEndpointPolicy implements EndpointPolicy {
    static final RuntimeEndpointPolicy INSTANCE = new RuntimeEndpointPolicy();
    private static final Set<String> RESERVED_ATTRIBUTES = Set.of(
            "authorization", "agentid", "tenantid", "userid", "spaceid",
            "x-agent-id", "x-tenant-id", "x-user-id", "x-space-id");

    private RuntimeEndpointPolicy() {
    }

    @Override
    public EndpointType type() {
        return EndpointType.RUNTIME;
    }

    @Override
    public String credential(String supplied) {
        return null;
    }

    @Override
    public TransportProvider.CreateCommand createCommand(TransportProvider.CreateCommand command) {
        Map<String, String> attributes = new LinkedHashMap<>();
        command.attributes().forEach((key, value) -> {
            if (key != null && !RESERVED_ATTRIBUTES.contains(key.toLowerCase(Locale.ROOT))) {
                attributes.put(key, value);
            }
        });
        return new TransportProvider.CreateCommand(command.invocationRef(), command.invocationId(),
                command.idempotencyKey(), command.conversationId(), null, command.mode(), command.input(),
                command.clientTools(), null, command.relatedTaskRef(), attributes);
    }

    @Override
    public boolean retryUnconfirmedCreate() {
        return false;
    }
}
