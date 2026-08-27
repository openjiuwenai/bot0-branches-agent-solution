/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest.abcde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.A2ASendCommand;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter.SseEvent;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for ABCDE protocol adapter - verifies flat {event, content, data} unwrapping.
 */
class EdpaAbcdeCustomRestAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EdpaAbcdeCustomRestAdapter adapter = new EdpaAbcdeCustomRestAdapter(objectMapper);

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

    // ===== DataPart extraction tests (新增) =====

    @Test
    @DisplayName("DataPart custom payload -> 提取 event/content/data")
    void dataPartCustomPayloadUnwrapsToFlatFormat() {
        var ctx = context(Map.of());

        // 构造 DataPart: {type:"custom", payload:{event:"think_chunk", content:"正在搜索", data:{...}}}
        Map<String, Object> payload = Map.of(
                "event", "think_chunk",
                "content", "正在搜索理财产品",
                "data", Map.of("source", "knowledge_base", "progress", "30%"));
        Map<String, Object> dataPartData = Map.of("type", "custom", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-think")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "think_chunk");
            assertThat(rsp).containsEntry("content", "正在搜索理财产品");
            assertThat(rsp.get("data")).isInstanceOfSatisfying(Map.class, data -> {
                assertThat(data).containsEntry("source", "knowledge_base");
                assertThat(data).containsEntry("progress", "30%");
            });
        });
    }

    @Test
    @DisplayName("DataPart llm_usage type -> 用 type 作为 event 名称")
    void dataPartLlmUsageTypeUsesTypeAsEvent() {
        var ctx = context(Map.of());

        // 构造 DataPart: {type:"llm_usage", payload:{content:JSON, ...}}，payload 中无 event 字段
        Map<String, Object> payload = Map.of(
                "content", "{\"prompt_tokens\":10,\"completion_tokens\":50}",
                "data", Map.of("usage_metadata", Map.of("prompt_tokens", 10, "completion_tokens", 50)));
        Map<String, Object> dataPartData = Map.of("type", "llm_usage", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-usage")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        // payload 中无 event 字段，应回退到 type="llm_usage" 作为 event 名称
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "llm_usage");
            assertThat(rsp).containsEntry("content", "{\"prompt_tokens\":10,\"completion_tokens\":50}");
        });
    }

    @Test
    @DisplayName("DataPart llm_output type -> 用 type 作为 event 名称")
    void dataPartLlmOutputTypeUsesTypeAsEvent() {
        var ctx = context(Map.of());

        Map<String, Object> payload = Map.of(
                "content", "工具调用结果",
                "data", Map.of("tool_calls", List.of(Map.of("name", "search_product"))));
        Map<String, Object> dataPartData = Map.of("type", "llm_output", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-llm-output")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "llm_output");
            assertThat(rsp).containsEntry("content", "工具调用结果");
        });
    }

    @Test
    @DisplayName("TextPart 非空时 DataPart 被忽略（TextPart 优先）")
    void textPartTakesPrecedenceOverDataPart() {
        var ctx = context(Map.of());

        // 构造同时包含 TextPart 和 DataPart 的 artifact
        Map<String, Object> payload = Map.of("event", "data_event", "content", "from DataPart");
        Map<String, Object> dataPartData = Map.of("type", "custom", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-mixed")
                .parts(List.of(
                        new TextPart("来自TextPart的内容"),
                        new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        // TextPart 非空，应走 TextPart 路径；DataPart 不应被提取
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "summary");
            assertThat(rsp).containsEntry("content", "来自TextPart的内容");
        });
    }

    @Test
    @DisplayName("DataPart tool_start 事件 -> event=tool_start")
    void dataPartToolStartEventUnwraps() {
        var ctx = context(Map.of());

        Map<String, Object> payload = Map.of(
                "event", "tool_start",
                "content", "正在获取理财产品列表...",
                "data", Map.of("tool_name", "search_products"));
        Map<String, Object> dataPartData = Map.of("type", "custom", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-tool-start")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "tool_start");
            assertThat(rsp).containsEntry("content", "正在获取理财产品列表...");
        });
    }

    @Test
    @DisplayName("DataPart final_answer_chunk 事件 -> event=final_answer_chunk")
    void dataPartFinalAnswerChunkUnwraps() {
        var ctx = context(Map.of());

        Map<String, Object> payload = Map.of(
                "event", "final_answer_chunk",
                "content", "根据您的需求，推荐以下产品");
        Map<String, Object> dataPartData = Map.of("type", "custom", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-answer")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "final_answer_chunk");
            assertThat(rsp).containsEntry("content", "根据您的需求，推荐以下产品");
        });
    }

    @Test
    @DisplayName("仅 DataPart 且 data 为非 Map JSON -> 回退为 summary")
    void dataPartNonMapDataFallsBackToSummary() {
        var ctx = context(Map.of());

        // 构造 DataPart data 为简单字符串（非 Map 结构），序列化后不是有效的 JSON 信封
        // 注意：DataPart 的 data() 返回 Map，所以这里用包含非 envelope 结构的 Map
        Map<String, Object> dataPartData = Map.of("not_type", "xxx", "not_payload", "yyy");

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-invalid")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        // 非 custom/llm_usage/llm_output 信封结构，parseArtifactJsonEnvelope 解析失败 -> 回退为 summary
        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "summary");
            // content 为 dataPartJson 字符串
            assertThat(rsp.get("content")).isInstanceOf(String.class);
        });
    }

    @Test
    @DisplayName("artifact 既无 TextPart 也无 DataPart -> artifact_empty")
    void artifactWithNoTextPartAndNoDataPartReturnsEmpty() {
        var ctx = context(Map.of());

        // 构造仅含空 TextPart 的 artifact（text 为空字符串）
        Artifact artifact = Artifact.builder()
                .artifactId("artifact-empty-text")
                .parts(List.of(new TextPart("")))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "artifact_empty");
            assertThat(rsp).containsEntry("content", "");
        });
    }

    @Test
    @DisplayName("DataPart 含 interrupt_id 字段 -> 透传到 flat 结果")
    void dataPartWithInterruptIdPassthrough() {
        var ctx = context(Map.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "interrupt_start");
        payload.put("content", "您的请求已收到。");
        payload.put("interrupt_id", "interrupt-001");
        payload.put("data", Map.of("reason", "user_confirmation"));
        Map<String, Object> dataPartData = Map.of("type", "custom", "index", 0, "payload", payload);

        Artifact artifact = Artifact.builder()
                .artifactId("artifact-interrupt")
                .parts(List.of(new DataPart(dataPartData)))
                .build();
        TaskArtifactUpdateEvent event = TaskArtifactUpdateEvent.builder()
                .taskId("task-1").contextId("conv-1").artifact(artifact).build();

        SseEvent sseEvent = adapter.fromA2AStreamEvent(event, ctx);
        Map<String, Object> envelope = map(sseEvent.data());

        assertThat(envelope.get("custom_rsp_data")).isInstanceOfSatisfying(Map.class, rsp -> {
            assertThat(rsp).containsEntry("event", "interrupt_start");
            assertThat(rsp).containsEntry("content", "您的请求已收到。");
            assertThat(rsp).containsEntry("interrupt_id", "interrupt-001");
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
