/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcomeMapper;

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

    void accept(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isStreaming) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(result, "result is required");
        Objects.requireNonNull(eventObserver, "eventObserver is required");
        try {
            if (event instanceof TaskUpdateEvent update) {
                acceptUpdate(update, result, eventObserver);
            } else if (event instanceof TaskEvent taskEvent) {
                acceptTask(taskEvent.getTask(), result, eventObserver, isStreaming);
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
            RemoteAgentCaller.EventObserver eventObserver) {
        if (result.isDone()) {
            return;
        }
        Object updateEvent = update.getUpdateEvent();
        if (updateEvent instanceof TaskArtifactUpdateEvent artifactUpdate) {
            eventObserver.onArtifact(artifactUpdate);
        } else if (updateEvent instanceof TaskStatusUpdateEvent statusUpdate) {
            eventObserver.onStatus(statusUpdate);
            outcomeMapper.mapTask(statusUpdate.taskId(), statusUpdate.status().state(), statusText(statusUpdate),
                    update.getTask(), false).ifPresent(result::complete);
        } else {
            LOG.debug("Unknown A2A task update event type: {}", typeName(updateEvent));
        }
    }

    private void acceptTask(Task task, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isStreaming) {
        if (result.isDone() || task == null || task.status() == null) {
            return;
        }
        if (task.artifacts() != null && (!isStreaming || !task.status().state().isFinal())) {
            for (Artifact artifact : task.artifacts()) {
                eventObserver.onArtifact(new TaskArtifactUpdateEvent(task.id(), artifact, task.contextId(),
                        false, true, Map.of()));
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
