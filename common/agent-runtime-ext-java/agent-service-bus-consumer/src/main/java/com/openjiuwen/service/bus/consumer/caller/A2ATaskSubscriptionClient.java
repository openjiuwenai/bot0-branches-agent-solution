/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskIdParams;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens the HTTP/SSE {@code SubscribeToTask} data channel used by the Agent Bus caller.
 *
 * @since 2026-08-13
 */
public final class A2ATaskSubscriptionClient {
    /**
     * Opens a Task subscription and returns its local lifecycle handle.
     *
     * @param request subscription endpoint, Task identifier, and request headers
     * @param eventConsumer consumer for each event received from the remote Task stream
     * @param completionHandler callback invoked when the remote stream completes
     * @param errorHandler callback invoked when the remote stream fails
     * @return local handle used to close the subscription and suppress subsequent callbacks
     * @throws NullPointerException if any callback or the request is {@code null}
     * @throws IllegalArgumentException if the endpoint is not an absolute HTTP(S) URI
     */
    public TaskSubscription subscribe(TaskSubscriptionRequest request, Consumer<ClientEvent> eventConsumer,
            Runnable completionHandler, Consumer<Throwable> errorHandler) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(eventConsumer, "eventConsumer is required");
        Objects.requireNonNull(completionHandler, "completionHandler is required");
        Objects.requireNonNull(errorHandler, "errorHandler is required");
        Client client = A2AClientSupport.create(agentCard(request.endpointUrl()), true);
        AtomicBoolean active = new AtomicBoolean(true);
        TaskSubscription subscription = new TaskSubscription(active, client);
        ClientCallContext context = new ClientCallContext(Map.of(), request.requestHeaders());
        boolean subscribed = false;
        try {
            A2AClientSupport.withApplicationClassLoader(() -> {
                client.subscribeToTask(new TaskIdParams(request.taskId()),
                        List.of((event, ignored) -> {
                            if (active.get()) {
                                eventConsumer.accept(event);
                            }
                        }), failure -> {
                            if (!active.compareAndSet(true, false)) {
                                return;
                            }
                            if (failure == null) {
                                completionHandler.run();
                            } else {
                                errorHandler.accept(failure);
                            }
                        }, context);
                return null;
            });
            subscribed = true;
            return subscription;
        } finally {
            if (!subscribed) {
                subscription.close();
            }
        }
    }

    private static AgentCard agentCard(String endpointUrl) {
        String endpoint = a2aEndpoint(endpointUrl);
        return AgentCard.builder().name("agent-bus-task-subscription")
                .description("Agent Bus A2A task subscription").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Collections.emptyMap()).securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint, null, "1.0")))
                .url(endpoint).preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    static String a2aEndpoint(String value) {
        String endpoint = require(value, "endpointUrl");
        URI uri = URI.create(endpoint).normalize();
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("endpointUrl must be an absolute HTTP(S) URI");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("endpointUrl must not contain a query or fragment");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/a2a") ? normalized : normalized + "/a2a";
    }

    /** Coordinates for one standard A2A Task subscription. */
    public record TaskSubscriptionRequest(String endpointUrl, String taskId, Map<String, String> requestHeaders) {
        public TaskSubscriptionRequest {
            require(endpointUrl, "endpointUrl");
            require(taskId, "taskId");
            requestHeaders = requestHeaders == null || requestHeaders.isEmpty() ? Map.of() : Map.copyOf(requestHeaders);
        }

        /**
         * Creates a subscription without transport-specific request headers.
         *
         * @param endpointUrl remote Runtime endpoint URL
         * @param taskId identifier of the Task to subscribe to
         * @throws IllegalArgumentException if either argument is blank
         */
        public TaskSubscriptionRequest(String endpointUrl, String taskId) {
            this(endpointUrl, taskId, Map.of());
        }
    }

    /** Local handle that suppresses callbacks after its owning call completes. */
    public static final class TaskSubscription implements AutoCloseable {
        private final AtomicBoolean active;
        private final Client client;

        private TaskSubscription(AtomicBoolean active, Client client) {
            this.active = active;
            this.client = client;
        }

        @Override
        public void close() {
            active.set(false);
            try {
                client.close();
            } catch (A2AException | IllegalStateException ignored) {
                // Logical subscription is closed; transport cleanup is best effort.
            }
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
