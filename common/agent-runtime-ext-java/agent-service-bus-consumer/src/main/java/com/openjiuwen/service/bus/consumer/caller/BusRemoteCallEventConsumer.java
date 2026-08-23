/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Applies A2A SSE events received by the Bus Caller to the Runtime event SPI and outcome.
 *
 * @since 2026-08-11
 */
final class BusRemoteCallEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(BusRemoteCallEventConsumer.class);

    private final RemoteCallOutcomeMapper outcomeMapper = new RemoteCallOutcomeMapper();

    /**
     * Per-call record of whether any artifact has already reached the Runtime observer.
     *
     * <p>One {@code BusRemoteCallEventConsumer} is shared by every concurrent call of a caller, so
     * this state cannot live on the consumer itself; it belongs to the pending call.
     *
     * <p>Implementations must be thread-safe: artifacts are delivered both from the SDK's HTTP/SSE
     * callback thread ({@code acceptUpdate}) and from the Bus consumer thread ({@code acceptTask}
     * via {@code A2A_CALL_TERMINAL}), and the fast-terminal case is exactly when those two threads
     * interleave.
     */
    interface ArtifactDelivery {
        /**
         * Reports whether an artifact has already been handed to the observer for this call.
         *
         * @return true once at least one artifact was delivered
         */
        boolean hasDelivered();

        /**
         * Records that an artifact reached the observer.
         */
        void markDelivered();
    }

    void accept(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, ArtifactDelivery delivery) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(result, "result is required");
        Objects.requireNonNull(eventObserver, "eventObserver is required");
        Objects.requireNonNull(delivery, "delivery is required");
        try {
            if (event instanceof TaskUpdateEvent update) {
                acceptUpdate(update, result, eventObserver, delivery);
            } else if (event instanceof TaskEvent taskEvent) {
                acceptTask(taskEvent.getTask(), result, eventObserver, delivery);
            } else if (event instanceof MessageEvent messageEvent) {
                acceptMessage(messageEvent.getMessage(), result);
            } else {
                LOG.debug("Unknown A2A client event type: {}", event.getClass().getSimpleName());
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            result.completeExceptionally(failure);
        }
    }

    private void acceptUpdate(TaskUpdateEvent update, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, ArtifactDelivery delivery) {
        if (result.isDone()) {
            return;
        }
        Object updateEvent = update.getUpdateEvent();
        if (updateEvent instanceof TaskArtifactUpdateEvent artifactUpdate) {
            eventObserver.onArtifact(artifactUpdate);
            delivery.markDelivered();
        } else if (updateEvent instanceof TaskStatusUpdateEvent statusUpdate) {
            eventObserver.onStatus(statusUpdate);
            outcomeMapper.mapTask(statusUpdate.taskId(), statusUpdate.status().state(), statusText(statusUpdate),
                    update.getTask(), false).ifPresent(result::complete);
        } else {
            LOG.debug("Unknown A2A task update event type: {}", typeName(updateEvent));
        }
    }

    private void acceptTask(Task task, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, ArtifactDelivery delivery) {
        if (result.isDone() || task == null || task.status() == null) {
            return;
        }
        // Replay the snapshot's artifacts only when nothing has reached the observer yet. Keying on
        // delivery rather than on terminal state is what makes the fast-terminal case work: there the
        // SubscribeToTask subscription never succeeded, so this snapshot is the only carrier of the
        // stream's artifacts. Once the subscription did deliver frames, the snapshot would only
        // repeat them.
        if (task.artifacts() != null && !delivery.hasDelivered()) {
            for (Artifact artifact : task.artifacts()) {
                eventObserver.onArtifact(new TaskArtifactUpdateEvent(task.id(), artifact, task.contextId(),
                        false, true, Map.of()));
                delivery.markDelivered();
            }
        }
        eventObserver.onStatus(new TaskStatusUpdateEvent(task.id(), task.status(), task.contextId(), Map.of()));
        outcomeMapper.mapTask(task, false).ifPresent(result::complete);
    }

    private void acceptMessage(Message message, CompletableFuture<RemoteCallOutcome> result) {
        if (!result.isDone()) {
            outcomeMapper.mapMessage(message).ifPresent(result::complete);
        }
    }

    private static String statusText(TaskStatusUpdateEvent event) {
        if (event.status().message() == null) {
            return "";
        }
        List<Part<?>> parts = event.status().message().parts();
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
