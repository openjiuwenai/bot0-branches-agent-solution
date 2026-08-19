/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEventBridgeTest {

    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;
        volatile boolean cancelled;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onComplete() {
            this.completed = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static TaskArtifactUpdateEvent artifactEvent(String text) {
        Artifact artifact = new Artifact("art-1", null, null,
                List.of(new TextPart(text)), Map.of(), List.of());
        return new TaskArtifactUpdateEvent("task-1", artifact, "ctx-1", null, null, Map.of());
    }

    private static TaskStatusUpdateEvent statusEvent(String text) {
        TaskStatus status = new TaskStatus(TaskState.TASK_STATE_WORKING,
                text == null ? null : new org.a2aproject.sdk.spec.Message(
                        org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT,
                        List.of(new TextPart(text)), "msg-1", null, null, List.of(), Map.of(), List.of()),
                OffsetDateTime.now());
        return new TaskStatusUpdateEvent("task-1", status, "ctx-1", Map.of());
    }

    @Test
    void artifactTextForwardedAsTypeChunk() {
        RecordingObserver observer = new RecordingObserver();
        new DownstreamEventBridge(observer).onArtifact(artifactEvent("partial answer"));
        assertThat(observer.chunks).hasSize(1);
        assertThat(observer.chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_CHUNK);
        assertThat(observer.chunks.get(0).getData()).isEqualTo("partial answer");
    }

    @Test
    void statusMessageTextForwardedAsTypeChunk() {
        RecordingObserver observer = new RecordingObserver();
        new DownstreamEventBridge(observer).onStatus(statusEvent("working"));
        assertThat(observer.chunks).hasSize(1);
        assertThat(observer.chunks.get(0).getData()).isEqualTo("working");
    }

    @Test
    void nullStatusMessageIgnored() {
        RecordingObserver observer = new RecordingObserver();
        new DownstreamEventBridge(observer).onStatus(statusEvent(null));
        assertThat(observer.chunks).isEmpty();
    }

    @Test
    void blankTextIgnored() {
        RecordingObserver observer = new RecordingObserver();
        new DownstreamEventBridge(observer).onArtifact(artifactEvent("  "));
        assertThat(observer.chunks).isEmpty();
    }

    @Test
    void notInScopeEnvelopeSuppressedFromForwarding() {
        // 协议信号不透传给最终用户（spec 2.2/4.4）：executor 在 outcome.result 上检测后重识别
        RecordingObserver observer = new RecordingObserver();
        new DownstreamEventBridge(observer).onArtifact(artifactEvent(
                HandoffSignals.notInScopeEnvelope(new IntentHandoff("不在范围", "x", "y", null, null, "{}"))));
        new DownstreamEventBridge(observer).onArtifact(artifactEvent("业务答案"));
        assertThat(observer.chunks).hasSize(1);
        assertThat(String.valueOf(observer.chunks.get(0).getData())).isEqualTo("业务答案");
    }

    @Test
    void cancelledObserverSuppressesForwardingAndCancelsBoundFuture() {
        RecordingObserver observer = new RecordingObserver();
        observer.cancelled = true;
        DownstreamEventBridge bridge = new DownstreamEventBridge(observer);
        java.util.concurrent.CompletableFuture<java.lang.Object> future = new java.util.concurrent.CompletableFuture<>();
        bridge.bindFuture(future);
        bridge.onArtifact(artifactEvent("late chunk"));
        assertThat(observer.chunks).isEmpty();
        assertThat(future).isCancelled(); // 协作式取消：尽力终止下游调用（spec 4.6）
    }
}
