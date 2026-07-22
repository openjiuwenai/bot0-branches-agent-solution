/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agenta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

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

        assertThat(command.conversationId()).isEqualTo("session-1");
        assertThat(command.params().tenant()).isNull();
        assertThat(command.stream()).isTrue();
        assertThat(((TextPart) command.params().message().parts().get(0)).text())
                .contains("\"query\":\"hello\"").contains("\"intent\":\"test\"");
        assertThat(command.params().metadata()).containsEntry("path_variables", context.pathVariables());
        assertThat(map(command.params().metadata().get("query")))
                .containsEntry("workspace_id", "11");
        assertThat(map(command.params().metadata().get("headers")))
                .containsEntry("stream", "true").containsEntry("x-language", List.of("zh-cn", "en"));
        assertThat(map(command.params().metadata().get("body")))
                .containsEntry("custom_data", customData);
    }

    @Test
    void responseDoesNotExposeInternalA2AContextId() {
        ObjectMapper objectMapper = new ObjectMapper();
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(objectMapper);
        var context = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "main", "conversation_id", "external-conversation"), Map.of(), Map.of());
        Task task = Task.builder().id("task").contextId("custom-rest:v1:internal-secret")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).history(List.of()).build();

        Map<String, Object> response = map(adapter.fromA2ATask(task, context));
        Task externalTask = (Task) response.get("custom_rsp_data");

        assertThat(response).containsEntry("conversation_id", "external-conversation");
        assertThat(externalTask.contextId()).isEqualTo("external-conversation");
    }

    @Test
    void responseExternalizesContextIdInNestedStatusMessages() {
        CustomRestDemoAdapter adapter = new CustomRestDemoAdapter(new ObjectMapper());
        var context = new CustomRestProtocolAdapter.Context(Map.of(),
                Map.of("agent_id", "main", "conversation_id", "external-conversation"), Map.of(), Map.of());
        var statusMessage = org.a2aproject.sdk.spec.Message.builder()
                .role(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT)
                .parts(new TextPart("done"))
                .messageId("status-message")
                .contextId("custom-rest:v1:internal-secret")
                .build();
        var status = new TaskStatus(TaskState.TASK_STATE_COMPLETED, statusMessage, null);
        Task task = Task.builder().id("task").contextId("custom-rest:v1:internal-secret")
                .status(status).history(List.of(statusMessage)).build();
        var statusEvent = new TaskStatusUpdateEvent("task", status,
                "custom-rest:v1:internal-secret", Map.of());

        Task externalTask = (Task) map(adapter.fromA2ATask(task, context)).get("custom_rsp_data");
        var streamEnvelope = map(adapter.fromA2AStreamEvent(statusEvent, context).data());
        var streamData = map(streamEnvelope.get("custom_rsp_data"));
        var externalStatusEvent = (TaskStatusUpdateEvent) streamData.get("data");

        assertThat(externalTask.status().message().contextId()).isEqualTo("external-conversation");
        assertThat(externalTask.history()).allSatisfy(message ->
                assertThat(message.contextId()).isEqualTo("external-conversation"));
        assertThat(externalStatusEvent.contextId()).isEqualTo("external-conversation");
        assertThat(externalStatusEvent.status().message().contextId()).isEqualTo("external-conversation");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
