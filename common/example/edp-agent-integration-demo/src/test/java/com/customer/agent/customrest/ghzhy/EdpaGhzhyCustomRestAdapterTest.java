/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest.ghzhy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.A2ASendCommand;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.SseEvent;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for GHZHY protocol adapter — verifies flat {event, content, data} unwrapping.
 */
class EdpaGhzhyCustomRestAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EdpaGhzhyCustomRestAdapter adapter = new EdpaGhzhyCustomRestAdapter(objectMapper);

    // ===== Request mapping tests (unchanged) =====

    @Test
    void mapsConversationIdFromPathVariable() {
        A2ASendCommand command = adapter.toA2ARequest(context(Map.of("input", "你好")));
        assertThat(command.params().message().contextId()).isEqualTo("session-1");
    }

    @Test
    void mapsConversationIdFromBodyFallback() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(), Map.of(),
                Map.of(), Map.of("conversation_id", "body-conv", "input", "你好"));
        assertThat(adapter.toA2ARequest(ctx).params().message().contextId()).isEqualTo("body-conv");
    }

    @Test
    void mapsConversionIdAliasFallback() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(), Map.of(),
                Map.of(), Map.of("conversion_id", "alias-conv", "input", "你好"));
        assertThat(adapter.toA2ARequest(ctx).params().message().contextId()).isEqualTo("alias-conv");
    }

    @Test
    void mapsInputToTextPart() {
        A2ASendCommand command = adapter.toA2ARequest(context(Map.of("input", "查询余额")));
        assertThat(command.params().message().parts()).first()
                .isInstanceOfSatisfying(TextPart.class, p -> assertThat(p.text()).isEqualTo("查询余额"));
    }

    @Test
    void mapsQueryAliasWhenInputMissing() {
        assertThat(adapter.toA2ARequest(context(Map.of("query", "转账5元")))
                .params().message().parts()).first()
                .isInstanceOfSatisfying(TextPart.class, p -> assertThat(p.text()).isEqualTo("转账5元"));
    }

    @Test
    void defaultsToStreamingWhenStreamMissing() {
        assertThat(adapter.toA2ARequest(context(Map.of("input", "你好"))).stream()).isTrue();
    }

    @Test
    void respectsStreamFalse() {
        assertThat(adapter.toA2ARequest(context(Map.of("input", "你好", "stream", false))).stream()).isFalse();
    }

    @Test
    void mapsTenantFromHeader() {
        var ctx = new CustomRestProtocolAdapter.Context(
                Map.of("x-tenant-id", List.of("tenant-1"), "x-user-id", List.of("user-1")),
                Map.of("conversation_id", "session-1"), Map.of(), Map.of("input", "你好"));
        assertThat(adapter.toA2ARequest(ctx).params().tenant()).isEqualTo("tenant-1");
    }

    // ===== Synchronous response (fromA2ATask) — flat format =====

    @Test
    void fromA2ATaskOutputsFlatEventContentData() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "edp", "conversation_id", "conv-1"), Map.of(), Map.of());
        Task task = Task.builder().id("task-1")
                .contextId("conv-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .history(List.of()).build();

        Map<String, Object> envelope = map(adapter.fromA2ATask(task, ctx));

        assertThat(envelope).containsEntry("success", true);
        assertThat(envelope).containsEntry("agent_id", "edp");
        assertThat(envelope).containsEntry("conversation_id", "conv-1");
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "task_completed");
            assertThat(rsp).containsKey("content");
            assertThat(rsp).containsKey("data");
        });
    }

    // ===== Stream event (fromA2AStreamEvent) — flat format =====

    @Test
    void taskStatusUpdateEventUnwrapstoFlatFormat() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "edp", "conversation_id", "conv-1"), Map.of(), Map.of());
        var submittedStatus = new TaskStatus(TaskState.TASK_STATE_SUBMITTED);
        var statusEvent = new TaskStatusUpdateEvent("task-1", submittedStatus, "conv-1", Map.of());

        SseEvent event = adapter.fromA2AStreamEvent(statusEvent, ctx);
        Map<String, Object> envelope = map(event.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "task_submitted");
            assertThat(rsp).containsEntry("content", "");
            assertThat(rsp.get("data")).isInstanceOfSatisfying(Map.class, data -> {
                assertThat(data).containsEntry("state", "TASK_STATE_SUBMITTED");
                assertThat(data).containsEntry("taskId", "task-1");
            });
        });
    }

    @Test
    void taskArtifactWithCustomEventUnwrapsToFlatFormat() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "edp", "conversation_id", "conv-1"), Map.of(), Map.of());

        // For Task type: verify unwrapTaskToFlat
        Task task = Task.builder().id("task-1").contextId("conv-1")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .history(List.of(
                        Message.builder().role(Message.Role.ROLE_AGENT)
                                .parts(new TextPart("推荐3款理财产品")).messageId("msg-1")
                                .contextId("conv-1").build()))
                .build();

        SseEvent event = adapter.fromA2AStreamEvent(task, ctx);
        Map<String, Object> envelope = map(event.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "task_working");
            assertThat(rsp).containsEntry("content", "推荐3款理财产品");
        });
    }

    @Test
    void finalTaskMapsToCompletedEvent() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("conversation_id", "conv-1"), Map.of(), Map.of());
        Task task = Task.builder().id("task-1").contextId("conv-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .history(List.of()).build();

        SseEvent event = adapter.fromA2AStreamEvent(task, ctx);
        assertThat(event.event()).isEqualTo("final");

        Map<String, Object> envelope = map(event.data());
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "task_completed");
        });
    }

    @Test
    void inputRequiredMapsToInterruptSseType() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("conversation_id", "conv-1"), Map.of(), Map.of());
        var interruptStatus = new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED);
        var interruptEvent = new TaskStatusUpdateEvent("task-1", interruptStatus, "conv-1", Map.of());

        SseEvent event = adapter.fromA2AStreamEvent(interruptEvent, ctx);
        assertThat(event.event()).isEqualTo("interrupt");

        Map<String, Object> envelope = map(event.data());
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "task_input_required");
        });
    }

    @Test
    void workingStatusMapsToChunkSseType() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("conversation_id", "conv-1"), Map.of(), Map.of());
        var workingStatus = new TaskStatus(TaskState.TASK_STATE_WORKING);
        var workingEvent = new TaskStatusUpdateEvent("task-1", workingStatus, "conv-1", Map.of());

        SseEvent event = adapter.fromA2AStreamEvent(workingEvent, ctx);
        assertThat(event.event()).isEqualTo("chunk");
    }

    // ===== Error response — flat format =====

    @Test
    void errorResponseOutputsFlatFormat() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "edp", "conversation_id", "conv-1"), Map.of(), Map.of());
        CustomRestProtocolAdapter.CustomRestError error =
                new CustomRestProtocolAdapter.CustomRestError(400, "invalid_request", "bad request");

        Map<String, Object> envelope = map(adapter.fromError(error, ctx));
        assertThat(envelope).containsEntry("success", false);
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "error");
            assertThat(rsp).containsEntry("content", "bad request");
            assertThat(rsp.get("data")).isInstanceOfSatisfying(Map.class, data -> {
                assertThat(data).containsEntry("code", "invalid_request");
            });
        });
    }

    @Test
    void streamErrorOutputFlatFormat() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "edp", "conversation_id", "conv-1"), Map.of(), Map.of());
        CustomRestProtocolAdapter.CustomRestError error =
                new CustomRestProtocolAdapter.CustomRestError(500, "internal_error", "server error");

        SseEvent event = adapter.fromStreamError(error, ctx);
        assertThat(event.event()).isEqualTo("error");

        Map<String, Object> envelope = map(event.data());
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "error");
        });
    }

    // ===== Business conversationId preservation =====

    @Test
    void flatFormatPreservesBusinessConversationId() {
        var ctx = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "main", "conversation_id", "external-conversation"),
                Map.of(), Map.of());
        Task task = Task.builder().id("task").contextId("external-conversation")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).history(List.of()).build();

        Map<String, Object> envelope = map(adapter.fromA2ATask(task, ctx));

        // The envelope's conversation_id should preserve the business id.
        assertThat(envelope).containsEntry("conversation_id", "external-conversation");
        // The projected task data should carry the same id.
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp.get("data")).isInstanceOfSatisfying(Map.class, data -> {
                assertThat(data).containsEntry("contextId", "external-conversation");
            });
        });
    }

    // ===== Helper methods =====

    private static CustomRestProtocolAdapter.Context context(Map<String, Object> body) {
        return new CustomRestProtocolAdapter.Context(
                Map.of("x-user-id", List.of("user-1"), "x-space-id", List.of("space-1"),
                        "x-tenant-id", List.of("tenant-1")),
                Map.of("project_id", "project-1", "agent_id", "main_planner",
                        "conversation_id", "session-1"),
                Map.of("workspace_id", List.of("11"), "type", List.of("controller")),
                body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
