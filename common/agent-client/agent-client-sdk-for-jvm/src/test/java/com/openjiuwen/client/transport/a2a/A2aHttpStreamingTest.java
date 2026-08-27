/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.ErrorCodes;
import com.openjiuwen.client.api.calltree.DataPartSnapshot;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;
import com.openjiuwen.client.transport.spi.RawResponseEvent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming and interrupt A2A transport tests.
 *
 * @since 2026-08-27
 */
class A2aHttpStreamingTest {
    @Test
    void streamingContinueInputUsesStreamingPath() throws Exception {
        AtomicInteger streamingResumeCalls = new AtomicInteger();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            String taskId = request.path("params").path("message").path("taskId").asText("");
            if ("SendStreamingMessage".equals(method) && taskId.isBlank()) {
                A2aHttpTestSupport.sse(exchange, A2aHttpTestSupport.taskFrame("task-streaming",
                        "inherit-streaming", "TASK_STATE_INPUT_REQUIRED", ""));
                return;
            }
            if ("SendStreamingMessage".equals(method)) {
                streamingResumeCalls.incrementAndGet();
                A2aHttpTestSupport.sse(exchange, A2aHttpTestSupport.taskFrame("task-streaming",
                        "inherit-streaming", "TASK_STATE_COMPLETED",
                        ",\"message\":{\"parts\":[{\"text\":\"done\"}]},"
                                + "\"artifacts\":[{\"artifactId\":\"final-result\",\"parts\":[{"
                                + "\"data\":{\"result\":\"approved\"}}]}]"));
                return;
            }
            A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.streamingBody("inherit-streaming",
                    "TASK_STATE_COMPLETED"));
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5)))
                    .build()) {
                InvocationCall initial = client.invoke(InvocationRequest.builder()
                        .conversationId("inherit-streaming")
                        .mode(InvocationMode.STREAMING)
                        .input("need user input")
                        .build());
                AtomicReference<InvocationCall> continuation = new AtomicReference<>();
                CountDownLatch prompted = new CountDownLatch(1);
                initial.events().subscribe(streamingPromptSubscriber(client, initial, continuation, prompted));
                assertTrue(prompted.await(5, TimeUnit.SECONDS));
                InvocationSnapshot completed = continuation.get().completion().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, completed.state());
                assertEquals("approved", findDataResult(completed));
                InvocationSnapshot queried = client.getInvocation(initial.invocationRef())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, queried.state());
                assertEquals("approved", findDataResult(queried));
                assertEquals(1, streamingResumeCalls.get());
            }
        }
    }

    @Test
    void streamingRawResponseObserverGetsCreateAndResumeFrames() throws Exception {
        AtomicInteger streamingResumeCalls = new AtomicInteger();
        List<RawResponseEvent> observed = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        var executor = A2aHttpTestSupport.observerExecutor("a2a-stream-observer-test");
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            String taskId = request.path("params").path("message").path("taskId").asText("");
            if ("SendStreamingMessage".equals(method) && taskId.isBlank()) {
                A2aHttpTestSupport.sse(exchange, A2aHttpTestSupport.taskFrame("task-streaming",
                        "observe-stream", "TASK_STATE_INPUT_REQUIRED", ""));
            } else if ("SendStreamingMessage".equals(method)) {
                streamingResumeCalls.incrementAndGet();
                A2aHttpTestSupport.sse(exchange, A2aHttpTestSupport.taskFrame("task-streaming",
                        "observe-stream", "TASK_STATE_COMPLETED",
                        ",\"message\":{\"parts\":[{\"text\":\"done\"}]}"));
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.streamingBody("observe-stream",
                        "TASK_STATE_COMPLETED"));
            }
        })) {
            try {
                A2aHttpTransportProvider transport = new A2aHttpTransportProvider(server.baseUrl(),
                        A2aHttpTestSupport.MAPPER, Duration.ofSeconds(5), RuntimeEndpointPolicy.INSTANCE,
                        Duration.ofSeconds(5), com.openjiuwen.client.api.RetryPolicy.defaults(), event -> {
                            observed.add(event);
                            received.countDown();
                        }, executor, 100, Duration.ofSeconds(2));
                try (AgentClient client = AgentClients.builder().transport(transport).build()) {
                    InvocationCall initial = client.invoke(InvocationRequest.builder()
                            .conversationId("observe-stream")
                            .mode(InvocationMode.STREAMING)
                            .input("need user input")
                            .build());
                    AtomicReference<InvocationCall> continuation = new AtomicReference<>();
                    CountDownLatch prompted = new CountDownLatch(1);
                    initial.events().subscribe(streamingPromptSubscriber(client, initial, continuation, prompted));
                    assertTrue(prompted.await(5, TimeUnit.SECONDS));
                    assertNotNull(continuation.get());
                    assertEquals(TaskState.COMPLETED,
                            continuation.get().completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
                    assertTrue(received.await(3, TimeUnit.SECONDS));
                }
            } finally {
                executor.shutdownNow();
            }
        }
        assertEquals(2, observed.size());
        assertEquals(RawResponseEvent.Source.CREATE_STREAM, observed.get(0).source());
        assertEquals(RawResponseEvent.Source.RESUME_STREAM, observed.get(1).source());
    }

    @Test
    void incompleteClientToolInterruptIsDiagnosed() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) ->
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.inputRequiredWithClientTool()))) {
            try (AgentClient client = AgentClients.builder()
                    .endpointType(com.openjiuwen.client.api.EndpointType.RUNTIME)
                    .endpointUrl(server.baseUrl())
                    .build()) {
                client.tools().register(LocalToolDescriptor.builder("local.echo").displayName("echo")
                        .description("echo").build(), (invocation, context) -> {
                            executions.incrementAndGet();
                            return ToolExecutionRecord.ok(invocation.toolCallId(), java.util.Map.of());
                        });
                client.exposeInConversation("incomplete-tool", ToolExposurePolicy.allow("local.echo"));
                InvocationCall call = client.invoke(InvocationRequest.builder()
                        .conversationId("incomplete-tool")
                        .mode(InvocationMode.BLOCKING)
                        .input("hello")
                        .build());
                List<InvocationEvent> events = new CopyOnWriteArrayList<>();
                CountDownLatch diagnosed = new CountDownLatch(1);
                call.events().subscribe(collectingSubscriber(events, diagnosed));
                InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                diagnosed.await(1, TimeUnit.SECONDS);
                assertEquals(0, executions.get());
                assertEquals(TaskState.INPUT_REQUIRED, snapshot.state());
                assertTrue(events.stream().anyMatch(event ->
                        event instanceof InvocationEvent.ProtocolDiagnostic diagnostic
                                && ErrorCodes.INPUT_RESUME_TARGET_MISSING.equals(diagnostic.code())));
            }
        }
    }

    private static String findDataResult(InvocationSnapshot snapshot) {
        assertNotNull(snapshot.callTree(), "STREAMING terminal snapshot must retain its call tree");
        return snapshot.callTree().root().artifacts().stream()
                .flatMap(artifact -> artifact.parts().stream())
                .filter(DataPartSnapshot.class::isInstance)
                .map(DataPartSnapshot.class::cast)
                .map(DataPartSnapshot::data)
                .map(data -> ((java.util.Map<?, ?>) data).get("result"))
                .map(String::valueOf)
                .findFirst()
                .orElse(null);
    }

    private static Flow.Subscriber<InvocationEvent> streamingPromptSubscriber(
            AgentClient client, InvocationCall initial,
            AtomicReference<InvocationCall> continuation, CountDownLatch prompted) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.InputRequired ir && ir.toolCall() == null
                        && continuation.get() == null) {
                    continuation.set(client.continueInput(ContinueInputRequest.builder()
                            .conversationId("inherit-streaming")
                            .relatedInvocationRef(initial.invocationRef())
                            .input("user answer")
                            .build()));
                    prompted.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                prompted.countDown();
            }

            @Override
            public void onComplete() {
                prompted.countDown();
            }
        };
    }

    private static Flow.Subscriber<InvocationEvent> collectingSubscriber(List<InvocationEvent> events,
            CountDownLatch diagnosed) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent item) {
                events.add(item);
                if (item instanceof InvocationEvent.ProtocolDiagnostic) {
                    diagnosed.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
