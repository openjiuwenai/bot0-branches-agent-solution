/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.client.A2ATaskSubscriptionClient;

import org.a2aproject.sdk.client.ClientEvent;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adds the FEAT-017 stream reference to a standard A2A Task subscription.
 *
 * @since 2026-08-11
 */
final class BusTaskSubscriptionClient {
    static final String STREAM_REFERENCE_HEADER = "X-OpenJiuwen-Stream-Ref";

    private final A2ATaskSubscriptionClient delegate;

    /**
     * Creates the Bus-specific wrapper around the Runtime A2A subscription client.
     *
     * @param delegate standard A2A Task subscription client
     */
    BusTaskSubscriptionClient(A2ATaskSubscriptionClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
    }

    A2ATaskSubscriptionClient.TaskSubscription subscribe(SubscriptionRequest request,
            Consumer<ClientEvent> eventConsumer, Runnable completionHandler, Consumer<Throwable> errorHandler) {
        return delegate.subscribe(toA2aRequest(request), eventConsumer, completionHandler, errorHandler);
    }

    static A2ATaskSubscriptionClient.TaskSubscriptionRequest toA2aRequest(SubscriptionRequest request) {
        Objects.requireNonNull(request, "request is required");
        return new A2ATaskSubscriptionClient.TaskSubscriptionRequest(request.endpointUrl(), request.taskId(),
                Map.of(STREAM_REFERENCE_HEADER, request.streamReference()));
    }

    /** Coordinates for a Task stream authorized by a FEAT-017 stream reference. */
    record SubscriptionRequest(String endpointUrl, String taskId, String streamReference) {
        SubscriptionRequest {
            require(endpointUrl, "endpointUrl");
            require(taskId, "taskId");
            require(streamReference, "streamReference");
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
