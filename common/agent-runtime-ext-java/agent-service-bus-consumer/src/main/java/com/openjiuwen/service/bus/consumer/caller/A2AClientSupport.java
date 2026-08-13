/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.function.Supplier;

/** Builds A2A SDK clients inside the application class-loader boundary. */
final class A2AClientSupport {
    private A2AClientSupport() {
    }

    static Client create(AgentCard card, boolean streaming) {
        return withApplicationClassLoader(() -> Client.builder(card)
                .clientConfig(new ClientConfig.Builder().setStreaming(streaming).build())
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig()).build());
    }

    static <T> T withApplicationClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader applicationClassLoader = A2AClientSupport.class.getClassLoader();
        if (applicationClassLoader == null || original == applicationClassLoader) {
            return action.get();
        }
        try {
            thread.setContextClassLoader(applicationClassLoader);
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
