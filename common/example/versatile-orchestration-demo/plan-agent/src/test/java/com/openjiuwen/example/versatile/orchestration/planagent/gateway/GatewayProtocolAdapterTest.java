/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.Context;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.CustomRestError;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Locks request mapping, blocking/error projection, and the byte-exact SSE data string for
 * streaming events. Uses the package-private clock-injecting constructor for determinism.
 *
 * @since 0.2.0
 */
class GatewayProtocolAdapterTest {
    private static final LongSupplier FIXED_CLOCK = () -> 1719000000000L;

    private final GatewayProtocolAdapter adapter =
            new GatewayProtocolAdapter(new ObjectMapper(), FIXED_CLOCK);

    private static Context contextWith(String agentId, String conversationId, Map<String, Object> body) {
        Map<String, String> path = new LinkedHashMap<>();
        if (agentId != null) {
            path.put("agent_id", agentId);
        }
        if (conversationId != null) {
            path.put("conversation_id", conversationId);
        }
        return new Context(Map.of(), path, Map.of(), body);
    }

    private static Context contextWithQuery(String agentId, String conversationId,
                                            Map<String, Object> body, Map<String, List<String>> queryParams) {
        Map<String, String> path = new LinkedHashMap<>();
        if (agentId != null) {
            path.put("agent_id", agentId);
        }
        if (conversationId != null) {
            path.put("conversation_id", conversationId);
        }
        return new Context(Map.of(), path, queryParams, body);
    }

    @Test
    void toA2ARequestMapsQueryConversationStreamAndMetadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_name", "MobileClient");
        body.put("input", Map.of("query", "查尾号为4241的卡的余额"));
        body.put("agent_id", "agent-1");
        body.put("conversation_id", "conv-1");
        body.put("stream", true);
        body.put("custom_data", Map.of("inputs", Map.of("query", "查尾号为4241的卡的余额")));
        body.put("unexpected_extra_field", "ignored");

        var command = adapter.toA2ARequest(contextWith("agent-1", "conv-1", body));

        assertThat(command.stream()).isTrue();
        assertThat(command.params().message().contextId()).isEqualTo("conv-1");
        Message message = command.params().message();
        assertThat(message.role()).isEqualTo(Message.Role.ROLE_USER);
        assertThat(message.parts().get(0))
                .isInstanceOfSatisfying(TextPart.class,
                        part -> assertThat(part.text()).isEqualTo("查尾号为4241的卡的余额"));
        assertThat(message.messageId()).isNotBlank();
        assertThat(command.params().metadata()).containsKey("custom_data");
        assertThat(command.params().metadata()).containsKey("body");
        assertThat(command.params().metadata()).containsEntry("agent_id", "agent-1");
    }

    @Test
    void toA2ARequestCarriesQueryParamsInMetadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Map.of("query", "hi"));
        body.put("conversation_id", "conv-1");
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        queryParams.put("type", List.of("controller"));
        queryParams.put("workspace_id", List.of("12"));
        queryParams.put("repeated", List.of("first", "second"));

        var command = adapter.toA2ARequest(contextWithQuery("agent-1", "conv-1", body, queryParams));

        @SuppressWarnings("unchecked")
        Map<String, String> queryMeta =
                (Map<String, String>) command.params().metadata().get("query");
        assertThat(queryMeta)
                .containsEntry("type", "controller")
                .containsEntry("workspace_id", "12")
                // Multi-valued param: the first value wins.
                .containsEntry("repeated", "first");
    }

    @Test
    void toA2ARequestCarriesEmptyQueryMapWhenNoParamsPresent() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Map.of("query", "hi"));
        body.put("conversation_id", "conv-1");

        var command = adapter.toA2ARequest(contextWith("agent-1", "conv-1", body));

        assertThat(command.params().metadata().get("query")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> queryMeta =
                (Map<String, String>) command.params().metadata().get("query");
        assertThat(queryMeta).isEmpty();
    }

    @Test
    void toA2ARequestStreamTrueWhenAbsentAndUsesPathConversationId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Map.of("query", "hi"));

        var command = adapter.toA2ARequest(contextWith("agent-1", "conv-from-path", body));

        assertThat(command.stream()).isTrue();
        assertThat(command.params().message().contextId()).isEqualTo("conv-from-path");
        assertThat(command.params().message().parts().get(0))
                .isInstanceOfSatisfying(TextPart.class,
                        part -> assertThat(part.text()).isEqualTo("hi"));
    }

    @Test
    void toA2ARequestReadsStreamFalseExplicitly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Map.of("query", "hi"));
        body.put("stream", false);
        body.put("conversation_id", "conv-1");

        assertThat(adapter.toA2ARequest(contextWith("agent-1", "conv-1", body)).stream()).isFalse();
    }

    @Test
    void fromA2AStreamEventProjectsLlmReasoningAsByteExactSseData() {
        TaskArtifactUpdateEvent event = artifactEvent(
                "{\"type\":\"llm_reasoning\",\"index\":2,\"payload\":{\"result_type\":\"answer\","
                        + "\"content\":\"用户\",\"createdTime\":1719000000000}}");

        var sse = adapter.fromA2AStreamEvent(event, contextWith("agent-1", "conv-1", Map.of()));

        // Null event name → Spring omits the event: line → byte-exact data:<json> frame.
        assertThat(sse.event()).isNull();
        assertThat(sse.data()).isEqualTo("{\"success\":true,\"agent_id\":\"agent-1\","
                + "\"conversation_id\":\"conv-1\",\"output\":\"\",\"error\":\"\","
                + "\"execution_time\":\"\",\"custom_rsp_data\":{\"event\":\"think_chunk\","
                + "\"content\":\"用户\",\"createdTime\":1719000000000,\"latency\":\"\","
                + "\"plugin\":\"\",\"data\":{}}}");
    }

    @Test
    void fromA2AStreamEventIgnoresLlmUsageAsEmptyEnvelope() {
        TaskArtifactUpdateEvent event = artifactEvent("{\"type\":\"llm_usage\",\"payload\":{}}");

        var sse = adapter.fromA2AStreamEvent(event, contextWith("agent-1", "conv-1", Map.of()));

        assertThat(sse.event()).isNull();
        // data() is typed Object on SseEvent; narrow to String for substring assertion.
        assertThat(sse.data()).asString().contains("\"custom_rsp_data\":{}");
    }

    @Test
    void fromA2ATaskReturnsEnvelopeMapWithFirstArtifactText() {
        Task task = Task.builder()
                .id("t").contextId("c")
                // SDK Task.Builder.build() requires a non-null status.
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(Artifact.builder().artifactId("a")
                        .parts(List.of(new TextPart("{\"type\":\"llm_output\",\"payload\":{\"content\":\"hi\"}}")))
                        .build()))
                .build();

        Object result = adapter.fromA2ATask(task, contextWith("agent-1", "conv-1", Map.of()));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) result;
        assertThat(env).containsEntry("success", true);
        assertThat(env).containsEntry("custom_rsp_data",
                "{\"type\":\"llm_output\",\"payload\":{\"content\":\"hi\"}}");
    }

    @Test
    void fromErrorReturnsEnvelopeMapWithErrorPayload() {
        Object result = adapter.fromError(
                new CustomRestError(400, "invalid_custom_request", "conversationId is required"),
                contextWith("agent-1", "conv-1", Map.of()));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) result;
        assertThat(env).containsEntry("success", false);
        assertThat(env).containsEntry("error", "conversationId is required");
        assertThat(env).containsEntry("conversation_id", "conv-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) env.get("custom_rsp_data");
        assertThat(custom).containsEntry("event", "error");
    }

    @Test
    void fromStreamErrorReturnsByteExactSseData() {
        var sse = adapter.fromStreamError(
                new CustomRestError(500, "adapter_execution_failed", "boom"),
                contextWith("agent-1", "conv-1", Map.of()));

        assertThat(sse.event()).isNull();
        assertThat(sse.data()).isEqualTo("{\"success\":false,\"agent_id\":\"agent-1\","
                + "\"conversation_id\":\"conv-1\",\"output\":\"\",\"error\":\"boom\","
                + "\"execution_time\":\"\",\"custom_rsp_data\":{\"event\":\"error\","
                + "\"data\":{\"message\":\"boom\"}}}");
    }

    private static TaskArtifactUpdateEvent artifactEvent(String text) {
        Artifact artifact = Artifact.builder().artifactId("a")
                .parts(List.of(new TextPart(text))).build();
        return TaskArtifactUpdateEvent.builder()
                .taskId("t").contextId("c").artifact(artifact).build();
    }
}
