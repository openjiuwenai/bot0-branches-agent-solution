/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Locks {@link GatewayStreamProjector}'s per-{@code type} dispatch with a fixed clock.
 *
 * @since 0.2.0
 */
class GatewayStreamProjectorTest {
    private static final LongSupplier FIXED_CLOCK = () -> 1719000000000L;

    private final GatewayStreamProjector projector =
            new GatewayStreamProjector(new ObjectMapper(), FIXED_CLOCK);

    @Test
    void llmReasoningBecomesThinkChunkUsingClockWhenCreatedTimeAbsent() {
        Map<String, Object> data = asMap(projector.customRspDataFor(artifactEvent(
                "{\"type\":\"llm_reasoning\",\"index\":2,"
                        + "\"payload\":{\"result_type\":\"answer\",\"content\":\"用户\"}}")));

        assertThat(data.keySet()).containsExactly(
                "event", "content", "createdTime", "latency", "plugin", "data");
        assertThat(data).containsEntry("event", "think_chunk");
        assertThat(data).containsEntry("content", "用户");
        assertThat(data).containsEntry("createdTime", 1719000000000L);
        assertThat(data).containsEntry("latency", "");
        assertThat(data).containsEntry("plugin", "");
        assertThat(data).containsEntry("data", Map.of());
    }

    @Test
    void llmReasoningUsesPayloadCreatedTimeLatencyPluginAndData() {
        Map<String, Object> data = asMap(projector.customRspDataFor(artifactEvent(
                "{\"type\":\"llm_reasoning\",\"payload\":{\"content\":\"hi\","
                        + "\"createdTime\":42,\"latency\":\"1ms\",\"plugin\":\"p\",\"data\":{\"k\":\"v\"}}}")));

        assertThat(data).containsEntry("createdTime", 42);
        assertThat(data).containsEntry("latency", "1ms");
        assertThat(data).containsEntry("plugin", "p");
        assertThat(data).containsEntry("data", Map.of("k", "v"));
    }

    @Test
    void llmUsageIsIgnorableEmptyMap() {
        assertThat(projector.customRspDataFor(artifactEvent(
                "{\"type\":\"llm_usage\",\"payload\":{}}"))).isEqualTo(Map.of());
    }

    @Test
    void otherTypeIsPassedThroughVerbatim() {
        Map<String, Object> data = asMap(projector.customRspDataFor(artifactEvent(
                "{\"type\":\"tool_call\",\"index\":1,\"payload\":{\"x\":1}}")));

        assertThat(data).containsEntry("type", "tool_call");
        assertThat(data).containsEntry("index", 1);
        assertThat(data).containsEntry("payload", Map.of("x", 1));
    }

    @Test
    void noTypeIsPassedThroughVerbatim() {
        Map<String, Object> data = asMap(projector.customRspDataFor(artifactEvent(
                "{\"event\":\"message\",\"data\":{\"text\":\"hi\"}}")));

        assertThat(data).containsEntry("event", "message");
        assertThat(data).containsEntry("data", Map.of("text", "hi"));
    }

    @Test
    void nonJsonTextFallsBackToTextWrapper() {
        assertThat(projector.customRspDataFor(artifactEvent("plain not json")))
                .isEqualTo(Map.of("text", "plain not json"));
    }

    @Test
    void taskStatusUpdateIsIgnorableEmptyMap() {
        TaskStatusUpdateEvent status = new TaskStatusUpdateEvent("t",
                new TaskStatus(TaskState.TASK_STATE_WORKING), "c", Map.of());

        assertThat(projector.customRspDataFor(status)).isEqualTo(Map.of());
    }

    @Test
    void artifactWithoutTextPartsIsIgnorableEmptyMap() {
        // The A2A SDK Artifact record forbids empty parts ("Parts cannot be empty"), so a
        // non-text DataPart is used to build an artifact that carries no projectable TextPart.
        Artifact empty = Artifact.builder().artifactId("a")
                .parts(List.of(new DataPart(Map.of("k", "v")))).build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("t").contextId("c").artifact(empty).build();

        assertThat(projector.customRspDataFor(event)).isEqualTo(Map.of());
    }

    private static TaskArtifactUpdateEvent artifactEvent(String text) {
        Artifact artifact = Artifact.builder().artifactId("a")
                .parts(List.of(new TextPart(text))).build();
        return TaskArtifactUpdateEvent.builder()
                .taskId("t").contextId("c").artifact(artifact).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
