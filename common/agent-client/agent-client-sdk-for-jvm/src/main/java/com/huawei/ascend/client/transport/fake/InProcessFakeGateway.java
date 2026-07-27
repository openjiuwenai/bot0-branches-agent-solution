/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.fake;

import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.transport.spi.ToolWireSpec;
import com.huawei.ascend.client.transport.spi.TransportProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内假网关（<b>测试工具，非 SDK 交付、不发起任何网络</b>）。
 *
 * <p>用途：在无网络环境下对 SDK 的多轮工具驱动、幂等去重、治理编排做纯逻辑单测。
 * 行为对齐 {@code mock-gateway}：依据 CreateCommand 携带的 ToolView（clientTools）按序请求各工具一次，
 * 全部完成后结束；并对首个工具故意重复投递一次 INPUT_REQUIRED 以验证客户端去重。
 * @since 2026-07-27
 */
public final class InProcessFakeGateway implements TransportProvider {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, FakeTask> byTaskRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FakeTask> byInvocationRef = new ConcurrentHashMap<>();

    @Override
    public Flow.Publisher<InvocationEvent> createAndStream(CreateCommand cmd) {
        FakeTask task = new FakeTask(cmd.invocationRef(), "task-" + UUID.randomUUID(),
                cmd.conversationId(), cmd.clientTools());
        byInvocationRef.put(cmd.invocationRef(), task);
        byTaskRef.put(task.taskRef, task);
        Runnable start = () -> {
            submit(task, new InvocationEvent.Accepted(task.invocationRef, task.taskRef, task.contextId));
            submit(task, new InvocationEvent.StatusChanged(task.invocationRef, TaskState.WORKING, false));
            advance(task, true);
        };
        return new LazyStartPublisher(task.publisher, start);
    }

    @Override
    public CompletionStage<InvocationSnapshot> resumeToolResult(ResumeCommand cmd) {
        FakeTask task = byTaskRef.get(cmd.taskRef());
        if (task == null) {
            CompletableFuture<InvocationSnapshot> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("no task " + cmd.taskRef()));
            return f;
        }
        task.index++;
        advance(task, false);
        return CompletableFuture.completedFuture(new InvocationSnapshot(
                task.invocationRef, TaskState.WORKING, false, task.taskRef, null, null, null, null));
    }

    private void advance(FakeTask task, boolean firstRound) {
        if (task.index < task.toolNames.size()) {
            String name = task.toolNames.get(task.index);
            InvocationEvent.ToolCall call = new InvocationEvent.ToolCall(
                    "call-" + task.taskRef + "-" + task.index, name, argsFor(task, name), null);
            submit(task, new InvocationEvent.StatusChanged(task.invocationRef, TaskState.INPUT_REQUIRED, false));
            submit(task, new InvocationEvent.InputRequired(task.invocationRef, call, null));
            if (firstRound) {
                // 故意重复投递一次，验证客户端"最多执行一次 / 最多续传一次"。
                submit(task, new InvocationEvent.InputRequired(task.invocationRef, call, null));
            }
        } else {
            submit(task, new InvocationEvent.Completed(task.invocationRef,
                    "completed after " + task.toolNames.size() + " client tool round(s)"));
            terminate(task);
        }
    }

    private Map<String, Object> argsFor(FakeTask task, String name) {
        Map<String, Object> args = new LinkedHashMap<>();
        String schema = task.schemas.get(name);
        if (schema != null) {
            try {
                JsonNode required = mapper.readTree(schema).path("required");
                if (required.isArray()) {
                    for (JsonNode k : required) {
                        args.put(k.asText(), "mock-" + k.asText());
                    }
                }
            } catch (JsonProcessingException ignore) {
                // 无 schema 时不填参数
            }
        }
        return args;
    }

    @Override
    public CompletionStage<InvocationSnapshot> getTask(String taskRef) {
        FakeTask task = byTaskRef.get(taskRef);
        TaskState st = (task != null) ? task.lastState : TaskState.UNKNOWN;
        String ref = (task != null) ? task.invocationRef : taskRef;
        return CompletableFuture.completedFuture(
                new InvocationSnapshot(ref, st, st.isTerminal(), taskRef, null, null, null, null));
    }

    @Override
    public CompletionStage<InvocationSnapshot> cancel(String taskRef, String reason) {
        FakeTask task = byTaskRef.get(taskRef);
        if (task != null) {
            submit(task, new InvocationEvent.StatusChanged(task.invocationRef, TaskState.CANCELED, true));
            terminate(task);
            return CompletableFuture.completedFuture(new InvocationSnapshot(
                    task.invocationRef, TaskState.CANCELED, true, taskRef, null, null, null, null));
        }
        return CompletableFuture.completedFuture(InvocationSnapshot.unknown(taskRef));
    }

    @Override
    public void close() {
        for (FakeTask t : byTaskRef.values()) {
            if (!t.publisher.isClosed()) {
                t.publisher.close();
            }
        }
        byTaskRef.clear();
        byInvocationRef.clear();
    }

    private void submit(FakeTask task, InvocationEvent event) {
        if (event instanceof InvocationEvent.StatusChanged sc) {
            task.lastState = sc.state();
        }
        if (!task.publisher.isClosed()) {
            task.publisher.submit(event);
        }
    }

    private void terminate(FakeTask task) {
        byTaskRef.remove(task.taskRef);
        byInvocationRef.remove(task.invocationRef);
        if (!task.publisher.isClosed()) {
            task.publisher.close();
        }
    }

    private static final class FakeTask {
        final String invocationRef;
        final String taskRef;
        final String contextId;
        final List<String> toolNames = new ArrayList<>();
        final Map<String, String> schemas = new LinkedHashMap<>();
        final SubmissionPublisher<InvocationEvent> publisher =
                new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize());
        volatile TaskState lastState = TaskState.SUBMITTED;
        int index = 0;

        FakeTask(String invocationRef, String taskRef, String contextId, List<ToolWireSpec> clientTools) {
            this.invocationRef = invocationRef;
            this.taskRef = taskRef;
            this.contextId = contextId;
            if (clientTools != null) {
                for (ToolWireSpec spec : clientTools) {
                    toolNames.add(spec.name());
                    schemas.put(spec.name(), spec.inputSchema());
                }
            }
        }
    }

    private static final class LazyStartPublisher implements Flow.Publisher<InvocationEvent> {
        private final SubmissionPublisher<InvocationEvent> delegate;
        private final Runnable start;
        private final AtomicBoolean started = new AtomicBoolean(false);

        LazyStartPublisher(SubmissionPublisher<InvocationEvent> delegate, Runnable start) {
            this.delegate = delegate;
            this.start = start;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super InvocationEvent> subscriber) {
            delegate.subscribe(subscriber);
            if (started.compareAndSet(false, true)) {
                start.run();
            }
        }
    }
}
