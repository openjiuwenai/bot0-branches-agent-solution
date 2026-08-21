/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.transport.spi.TransportProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Runtime 直连策略：只投影已类型化的 A2A/客户端工具字段，不透传任意 attributes。 */
final class RuntimeEndpointPolicy implements EndpointPolicy {
    static final RuntimeEndpointPolicy INSTANCE = new RuntimeEndpointPolicy();

    private RuntimeEndpointPolicy() {
    }

    @Override
    public EndpointType type() {
        return EndpointType.RUNTIME;
    }

    @Override
    public Optional<String> credential(String supplied) {
        return Optional.empty();
    }

    @Override
    public TransportProvider.CreateCommand createCommand(TransportProvider.CreateCommand command) {
        return new TransportProvider.CreateCommand(command.invocationRef(), command.invocationId(),
                command.idempotencyKey(), command.conversationId(), null, command.mode(), command.input(),
                command.clientTools(), null, command.relatedTaskRef(), runtimeAttributes(command.attributes()));
    }

    private static Map<String, String> runtimeAttributes(Map<String, String> source) {
        Map<String, String> projected = new LinkedHashMap<>();
        copyNonBlank(source, projected, "traceId");
        copyNonBlank(source, projected, "correlationId");
        return Map.copyOf(projected);
    }

    private static void copyNonBlank(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @Override
    public boolean retryUnconfirmedCreate() {
        return false;
    }

    @Override
    public boolean useSubscriptionForRecovery(InvocationMode mode) {
        return mode == InvocationMode.STREAMING;
    }

    @Override
    public boolean cursorReplaySupported() {
        return false;
    }
}
