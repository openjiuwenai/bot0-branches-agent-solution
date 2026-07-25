/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Verifies ReAct and Harness behavior against the adapter with real AgentScope execution.
 *
 * @since 2026-07-20
 */
class AgentScopeAgentIntegrationTest {
    private static final String CONVERSATION = "integration-session";

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @EnumSource(AgentKind.class)
    void mapsNormalAgentScopeResult(AgentKind kind) throws Exception {
        ScriptedModel model = new ScriptedModel(textResponse("done"));

        try (TestRuntime runtime = runtime(kind, model, new Toolkit())) {
            QueryResponse response = runtime.handler().query(request("hello"));

            assertThat(result(response)).containsEntry("content", "done").doesNotContainKey("_interrupt");
        }
    }

    @ParameterizedTest
    @EnumSource(AgentKind.class)
    void streamsConfirmationAndResumesWithNativeConfirmResult(AgentKind kind) throws Exception {
        CountingAskingTool tool = new CountingAskingTool("transfer");
        Toolkit toolkit = toolkitWith(tool);
        ScriptedModel model = new ScriptedModel(
            toolUseResponse("call-1", "transfer", Map.of("recipient", "Li Ming", "amount", 5)),
            textResponse("approved"));

        try (TestRuntime runtime = runtime(kind, model, toolkit)) {
            RecordingObserver observer = new RecordingObserver();
            runtime.handler().streamQuery(request("transfer"), observer);

            List<QueryChunk> interruptChunks = observer.chunks.stream()
                .filter(chunk -> QueryChunk.TYPE_INTERRUPT.equals(chunk.getType()))
                .toList();
            assertThat(interruptChunks).hasSize(1);
            QueryChunk interruptChunk = interruptChunks.get(0);
            Map<String, Object> interaction = interaction(interruptChunk.getData());
            assertInteraction(interaction, "confirmation", "transfer", null);
            assertThat(observer.completed).isEqualTo(1);
            assertThat(observer.errors).isEmpty();

            QueryResponse resumed = runtime.handler().query(resume("APPROVE", interaction));

            assertThat(result(resumed)).containsEntry("content", "approved").doesNotContainKey("_interrupt");
            assertThat(tool.invocations()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(AgentKind.class)
    void rejectsPendingToolWithoutExecutingIt(AgentKind kind) throws Exception {
        CountingAskingTool tool = new CountingAskingTool("transfer");
        ScriptedModel model = new ScriptedModel(
            toolUseResponse("call-1", "transfer", Map.of("amount", 5)),
            textResponse("rejected"));

        try (TestRuntime runtime = runtime(kind, model, toolkitWith(tool))) {
            Map<String, Object> first = result(runtime.handler().query(request("transfer")));
            assertThat(first).as("first reject result for %s", kind).containsKey("_interrupt");
            Map<String, Object> interaction = interaction(first.get("_interrupt"));

            QueryResponse resumed = runtime.handler().query(resume("REJECT", interaction));

            assertThat(result(resumed)).doesNotContainKey("_interrupt");
            assertThat(tool.invocations()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(AgentKind.class)
    void resumesSchemaOnlyToolWithExternalResult(AgentKind kind) throws Exception {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(ToolSchema.builder()
            .name("external_search")
            .description("Search in an external system")
            .parameters(Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string"))))
            .build());
        ScriptedModel model = new ScriptedModel(
            toolUseResponse("call-1", "external_search", Map.of("query", "agent runtime")),
            textResponse("external complete"));

        try (TestRuntime runtime = runtime(kind, model, toolkit)) {
            Map<String, Object> first = result(runtime.handler().query(request("search")));
            assertThat(first).as("first external-tool result for %s", kind).containsKey("_interrupt");
            Map<String, Object> interaction = interaction(first.get("_interrupt"));
            assertInteraction(
                interaction,
                "tool_result",
                "external_search",
                Map.of("query", "agent runtime"));

            QueryResponse resumed = runtime.handler().query(resume("external payload", interaction));

            assertThat(result(resumed))
                .containsEntry("content", "external complete")
                .doesNotContainKey("_interrupt");
            List<ToolResultBlock> externalResults = model.request(1).stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
            assertThat(externalResults).singleElement().satisfies(toolResult -> {
                assertThat(toolResult.getId()).isEqualTo("call-1");
                assertThat(toolResult.getName()).isEqualTo("external_search");
                assertThat(toolResult.getOutput()).singleElement()
                    .isInstanceOfSatisfying(TextBlock.class,
                        block -> assertThat(block.getText()).isEqualTo("external payload"));
            });
        }
    }

    @ParameterizedTest
    @EnumSource(AgentKind.class)
    void resumesMiddlewareStopWithEmptyAgentScopeInput(AgentKind kind) throws Exception {
        PauseOnceMiddleware middleware = new PauseOnceMiddleware();
        ScriptedModel model = new ScriptedModel(textResponse("pause marker"), textResponse("continued"));

        try (TestRuntime runtime = runtime(kind, model, new Toolkit(), middleware)) {
            RecordingObserver observer = new RecordingObserver();
            runtime.handler().streamQuery(request("pause"), observer);

            List<QueryChunk> interruptChunks = observer.chunks.stream()
                .filter(chunk -> QueryChunk.TYPE_INTERRUPT.equals(chunk.getType()))
                .toList();
            assertThat(interruptChunks).hasSize(1);
            QueryChunk interruptChunk = interruptChunks.get(0);
            Map<String, Object> interaction = interaction(interruptChunk.getData());
            assertThat(payload(interaction)).containsEntry("kind", "message");
            assertThat(observer.completed).isEqualTo(1);
            assertThat(observer.errors).isEmpty();

            QueryResponse resumed = runtime.handler().query(resume("continue", interaction));

            assertThat(result(resumed)).containsEntry("content", "continued").doesNotContainKey("_interrupt");
            assertThat(middleware.invocations()).isEqualTo(2);
        }
    }

    private TestRuntime runtime(AgentKind kind, ChatModelBase model, Toolkit toolkit,
        MiddlewareBase... middlewares) throws IOException {
        if (kind == AgentKind.REACT) {
            ReActAgent.Builder builder = ReActAgent.builder()
                .name("integration-react")
                .sysPrompt("Follow the scripted response.")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore());
            for (MiddlewareBase middleware : middlewares) {
                builder.middleware(middleware);
            }
            ReActAgent agent = builder.build();
            return new TestRuntime(AgentScopeAgentHandler.forReActAgent(agent), () -> { });
        }

        Path workspace = Files.createDirectories(tempDir.resolve("harness-" + System.nanoTime()));
        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name("integration-harness")
            .sysPrompt("Follow the scripted response.")
            .model(model)
            .toolkit(toolkit)
            .stateStore(new InMemoryAgentStateStore())
            .workspace(workspace)
            .disableFilesystemTools()
            .disableShellTool()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableSessionPersistence()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableSubagents()
            .disableDynamicSkills()
            .disableCompaction()
            .disableToolResultEviction();
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        HarnessAgent agent = builder.build();
        return new TestRuntime(AgentScopeAgentHandler.forHarnessAgent(agent), agent);
    }

    private static Toolkit toolkitWith(ToolBase tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static ServeRequest request(String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(CONVERSATION);
        request.setUserId("integration-user");
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        return request;
    }

    private static ServeRequest resume(String content, Map<String, Object> interaction) {
        ServeRequest request = request(content);
        request.getMetadata().put("_interrupt", interaction);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(QueryResponse response) {
        return (Map<String, Object>) response.getResult();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> interaction(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> interaction) {
        return (Map<String, Object>) interaction.get("payload");
    }

    @SuppressWarnings("unchecked")
    private static void assertInteraction(Map<String, Object> interaction, String kind, String toolName,
        Map<String, Object> expectedArguments) {
        Map<String, Object> payload = payload(interaction);
        assertThat(payload).containsEntry("kind", kind);
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item)
                .containsEntry("type", "tool_call")
                .containsEntry("name", toolName)
                .doesNotContainKey("id");
            if (expectedArguments == null) {
                assertThat(item).doesNotContainKey("arguments");
            } else {
                assertThat(item).containsEntry("arguments", expectedArguments);
            }
        });
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
            .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
            .build();
    }

    private static ChatResponse toolUseResponse(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
            .content(List.<ContentBlock>of(ToolUseBlock.builder().id(id).name(name).input(input).build()))
            .build();
    }

    private enum AgentKind {
        REACT,
        HARNESS
    }

    private record TestRuntime(AgentScopeAgentHandler handler, AutoCloseable resource) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            resource.close();
        }
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final List<ChatResponse> responses;
        private final List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String getModelName() {
            return "adapter-integration-script";
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
            requests.add(List.copyOf(messages));
            int current = index.getAndIncrement();
            if (current >= responses.size()) {
                return Flux.error(new IllegalStateException("No scripted response at index " + current));
            }
            return Flux.just(responses.get(current));
        }

        private List<Msg> request(int requestIndex) {
            return requests.get(requestIndex);
        }
    }

    private static final class CountingAskingTool extends ToolBase {
        private final AtomicInteger invocations = new AtomicInteger();

        private CountingAskingTool(String name) {
            super(name, "Requires confirmation", toolSchema(), false, true, false, null, false, false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("confirm"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invocations.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed"));
        }

        private int invocations() {
            return invocations.get();
        }

        private static Map<String, Object> toolSchema() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of(
                "recipient", Map.of("type", "string"),
                "amount", Map.of("type", "number")));
            return schema;
        }
    }

    private static final class PauseOnceMiddleware implements MiddlewareBase {
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
            invocations.incrementAndGet();
            if (paused.compareAndSet(false, true)) {
                return next.apply(input).concatWith(Flux.just(new RequestStopEvent(
                    "Review before continuing", GenerateReason.REASONING_STOP_REQUESTED)));
            }
            return next.apply(input);
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private static final class RecordingObserver implements QueryStreamObserver {
        private final List<QueryChunk> chunks = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private int completed;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        @Override
        public void onComplete() {
            completed++;
        }
    }
}
