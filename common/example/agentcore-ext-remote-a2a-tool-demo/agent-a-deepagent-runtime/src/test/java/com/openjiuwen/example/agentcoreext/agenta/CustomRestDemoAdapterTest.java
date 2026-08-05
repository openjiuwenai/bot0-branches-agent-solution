/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agenta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Verifies the example protocol adapter request and response mapping.
 *
 * @since 0.1.0
 */
class CustomRestDemoAdapterTest {
    @Test
    void mapsInputConversationAndTransportMetadata() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        Map<String, Object> customData = Map.of("inputs", Map.of("query", "hello"));
        var context = new CustomRestProtocolAdapter.Context(
                Map.of("stream", List.of("true"), "x-language", List.of("zh-cn", "en")),
                Map.of("project_id", "0", "agent_id", "main", "conversation_id", "session-1"),
                Map.of("workspace_id", List.of("11")),
                Map.of("agent_id", "main", "input", Map.of("query", "hello", "intent", "test"),
                        "stream", true, "custom_data", customData));

        var command = adapter.toA2ARequest(context);

        assertThat(command.params().message().contextId()).isEqualTo("session-1");
        assertThat(command.params().tenant()).isNull();
        assertThat(command.stream()).isTrue();
        assertThat(command.params().message().parts()).first()
                .isInstanceOfSatisfying(TextPart.class, part -> assertThat(part.text())
                        .contains("\"query\":\"hello\"").contains("\"intent\":\"test\""));
        assertThat(command.params().metadata()).containsEntry("path_variables", context.pathVariables());
        assertThat(map(command.params().metadata().get("query")))
                .containsEntry("workspace_id", "11");
        assertThat(map(command.params().metadata().get("headers")))
                .containsEntry("stream", "true").containsEntry("x-language", List.of("zh-cn", "en"));
        assertThat(map(command.params().metadata().get("body")))
                .containsEntry("custom_data", customData);
    }

    @Test
    void defaultsToStreamingWhenStreamIsMissing() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        var context = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("conversation_id", "session-1"), Map.of(), Map.of("input", "hello"));

        var command = adapter.toA2ARequest(context);

        assertThat(command.stream()).isTrue();
    }

    @Test
    void leavesMissingConversationForFrameworkValidation() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        var context = new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of("input", "hello"));

        var command = adapter.toA2ARequest(context);

        assertThat(command.params().message().contextId()).isNull();
    }

    @Test
    void mapsMissingInputToEmptyText() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        var context = new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of("conversation_id", "session-1"), Map.of(), Map.of());

        var command = adapter.toA2ARequest(context);

        assertThat(command.params().message().parts()).first()
                .isInstanceOfSatisfying(TextPart.class, part -> assertThat(part.text()).isEmpty());
    }

    @Test
    void responsePreservesBusinessConversationId() {
        ObjectMapper objectMapper = new ObjectMapper();
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(objectMapper);
        var context = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "main", "conversation_id", "external-conversation"), Map.of(), Map.of());
        Task task = Task.builder().id("task").contextId("external-conversation")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).history(List.of()).build();

        Map<String, Object> response = map(adapter.fromA2ATask(task, context));

        assertThat(response).containsEntry("conversation_id", "external-conversation");
        assertThat(response.get("custom_rsp_data")).isSameAs(task);
    }

    @Test
    void streamResponseDoesNotRebuildA2AEvent() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        var context = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "main", "conversation_id", "external-conversation"), Map.of(), Map.of());
        var status = new TaskStatus(TaskState.TASK_STATE_COMPLETED);
        var statusEvent = new TaskStatusUpdateEvent("task", status,
                "external-conversation", Map.of());

        var response = adapter.fromA2AStreamEvent(statusEvent, context);
        var streamEnvelope = map(response.data());
        var streamData = map(streamEnvelope.get("custom_rsp_data"));

        assertThat(response.event()).isEqualTo("final");
        assertThat(streamData.get("data")).isSameAs(statusEvent);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
