/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Verifies Bus SSE event forwarding through the Runtime {@link RemoteAgentCaller.EventObserver}. */
class BusRemoteCallEventConsumerTest {
    @Test
    void forwardsArtifactsAlreadyPresentInWorkingTaskSnapshot() {
        Task task = task(TaskState.TASK_STATE_WORKING, "first chunk");
        RecordingObserver observer = new RecordingObserver();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        new BusRemoteCallEventConsumer().accept(new TaskEvent(task), result, observer, new RecordingDelivery());

        assertThat(observer.artifacts).singleElement().satisfies(update ->
                assertThat(update.artifact().parts()).singleElement().isEqualTo(new TextPart("first chunk")));
        assertThat(observer.statuses).hasSize(1);
        assertThat(result).isNotDone();
    }

    @Test
    void doesNotReplayArtifactsAlreadyDeliveredByTheStream() {
        Task task = task(TaskState.TASK_STATE_COMPLETED, "final result");
        RecordingObserver observer = new RecordingObserver();
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.markDelivered();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        new BusRemoteCallEventConsumer().accept(new TaskEvent(task), result, observer, delivery);

        assertThat(observer.artifacts).isEmpty();
        assertThat(observer.statuses).hasSize(1);
        assertThat(result.join().result()).isEqualTo("final result");
    }

    @Test
    void replaysTerminalSnapshotArtifactsWhenNothingWasDeliveredYet() {
        Task task = task(TaskState.TASK_STATE_COMPLETED, "final result");
        RecordingObserver observer = new RecordingObserver();
        RecordingDelivery delivery = new RecordingDelivery();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        new BusRemoteCallEventConsumer().accept(new TaskEvent(task), result, observer, delivery);

        assertThat(observer.artifacts).singleElement().satisfies(update ->
                assertThat(update.artifact().parts()).singleElement().isEqualTo(new TextPart("final result")));
        assertThat(delivery.hasDelivered()).isTrue();
        assertThat(observer.statuses).hasSize(1);
        assertThat(result.join().result()).isEqualTo("final result");
    }

    @Test
    void marksDeliveryWhenAnArtifactUpdateReachesTheObserver() {
        RecordingObserver observer = new RecordingObserver();
        RecordingDelivery delivery = new RecordingDelivery();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        Artifact artifact = Artifact.builder().artifactId("artifact-1").parts(new TextPart("chunk")).build();
        TaskArtifactUpdateEvent update = new TaskArtifactUpdateEvent("task-1", artifact, "context-1",
                false, true, Map.of());
        Task snapshot = task(TaskState.TASK_STATE_WORKING, "chunk");

        new BusRemoteCallEventConsumer().accept(new TaskUpdateEvent(snapshot, update), result, observer, delivery);

        assertThat(observer.artifacts).hasSize(1);
        assertThat(delivery.hasDelivered()).isTrue();
        assertThat(result).isNotDone();
    }

    @Test
    void completesCallExceptionallyWhenObserverRejectsEvent() {
        Task task = task(TaskState.TASK_STATE_WORKING, "first chunk");
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        RemoteAgentCaller.EventObserver observer = new RemoteAgentCaller.EventObserver() {
            @Override
            public void onStatus(TaskStatusUpdateEvent event) {
                throw new IllegalStateException("observer rejected status");
            }

            @Override
            public void onArtifact(TaskArtifactUpdateEvent event) {
            }
        };

        new BusRemoteCallEventConsumer().accept(new TaskEvent(task), result, observer, new RecordingDelivery());

        assertThatThrownBy(result::join).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static Task task(TaskState state, String text) {
        Artifact artifact = Artifact.builder().artifactId("artifact-1").parts(new TextPart(text)).build();
        return Task.builder().id("task-1").contextId("context-1")
                .status(new TaskStatus(state)).artifacts(List.of(artifact)).build();
    }

    private static final class RecordingObserver implements RemoteAgentCaller.EventObserver {
        private final List<TaskStatusUpdateEvent> statuses = new ArrayList<>();
        private final List<TaskArtifactUpdateEvent> artifacts = new ArrayList<>();

        @Override
        public void onStatus(TaskStatusUpdateEvent event) {
            statuses.add(event);
        }

        @Override
        public void onArtifact(TaskArtifactUpdateEvent event) {
            artifacts.add(event);
        }
    }

    private static final class RecordingDelivery implements BusRemoteCallEventConsumer.ArtifactDelivery {
        private boolean delivered;

        @Override
        public boolean hasDelivered() {
            return delivered;
        }

        @Override
        public void markDelivered() {
            delivered = true;
        }
    }
}
