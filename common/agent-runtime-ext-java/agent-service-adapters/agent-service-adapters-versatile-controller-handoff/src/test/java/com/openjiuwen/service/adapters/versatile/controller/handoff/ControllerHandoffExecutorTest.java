/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerHandoffExecutorTest {

    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onComplete() { this.completed = true; }
    }

    /** 手工 fake：记录 RemoteCall，返回预置 outcome。 */
    static class FakeCaller implements RemoteAgentCaller {
        int calls;
        RemoteCall lastCall;
        EventObserver lastObserver;
        RemoteCallOutcome outcome = new RemoteCallOutcome("rt-9", TaskState.TASK_STATE_COMPLETED,
                "COMPLETED", "down answer", null, null);
        RuntimeException failure;

        @Override
        public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
            this.calls++;
            this.lastCall = call;
            this.lastObserver = eventObserver;
            CompletableFuture<RemoteCallOutcome> future = new CompletableFuture<>();
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(outcome);
            }
            return future;
        }
    }

    static final class EmptyProvider<T> implements ObjectProvider<T> {
        @Override public T getObject() { throw new IllegalStateException("none"); }
        @Override public T getObject(Object... args) { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
        @Override public Iterator<T> iterator() { return List.<T>of().iterator(); }
    }

    private ControllerHandoffProperties properties() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        p.getLoop().setMaxRedirects(3);
        p.getTarget().setAllowedAgents(List.of("agent_card_hotel", "agent_card_l1"));
        p.getTarget().setFixedL1Entry("agent_card_l1");
        p.setTimeout(Duration.ofSeconds(5));
        return p;
    }

    private ServeRequest request() {
        ServeRequest r = new ServeRequest();
        r.setConversationId("conv-1");
        r.setUserId("u1");
        r.setTenantId("t1");
        r.setSpaceId("s1");
        r.setStream(true);
        r.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));
        return r;
    }

    private IntentHandoff handoff() {
        return new IntentHandoff("L1_TO_L2", "intent_hotel", "hotel", "agent_card_hotel", "k1", "{}");
    }

    private ControllerHandoffExecutor executor(FakeCaller caller, ControllerHandoffProperties p) {
        return new ControllerHandoffExecutor(caller, new HandoffTargetResolver(p),
                new DownstreamEventMapper(), new HandoffLoopGuard(p),
                new EmptyProvider<>(), p);
    }

    @Test
    void buildsRemoteCallWithUserQueryAndConversation() {
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        executor(caller, properties()).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(caller.lastCall.agentName()).isEqualTo("agent_card_hotel");
        assertThat(caller.lastCall.message()).isEqualTo("订酒店");
        assertThat(caller.lastCall.contextId()).isEqualTo("conv-1");
        assertThat(caller.lastCall.taskId()).isNull();
        assertThat(caller.lastCall.isCallerStreaming()).isTrue();
        assertThat(caller.lastCall.metadata()).containsEntry("tenantId", "t1");
        assertThat(caller.lastCall.metadata()).containsEntry("handoffHopCount", 1);
    }

    @Test
    void completedOutcomeDrivesOnCompleteOnly() {
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        executor(caller, properties()).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
        // COMPLETED 不是 QueryChunk：除 bridge 转发外无额外 chunk
        assertThat(observer.chunks).isEmpty();
    }

    @Test
    void bridgesDownstreamChunks() {
        FakeCaller caller = new FakeCaller() {
            @Override
            public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
                lastCall = call;
                eventObserver.onStatus(new org.a2aproject.sdk.spec.TaskStatusUpdateEvent("t",
                        new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_WORKING,
                                new org.a2aproject.sdk.spec.Message(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT,
                                        List.of(new org.a2aproject.sdk.spec.TextPart("增量回答")), "m-1",
                                        null, null, List.of(), Map.of(), List.of()),
                                java.time.OffsetDateTime.now()),
                        "c", Map.of()));
                return CompletableFuture.completedFuture(outcome);
            }
        };
        RecordingObserver observer = new RecordingObserver();
        executor(caller, properties()).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(observer.chunks).hasSize(1);
        assertThat(observer.chunks.get(0).getData()).isEqualTo("增量回答");
        assertThat(observer.completed).isTrue();
    }

    @Test
    void inputRequiredDefaultGatedToUnsupportedFailure() {
        FakeCaller caller = new FakeCaller();
        caller.outcome = new RemoteCallOutcome("rt-9", TaskState.TASK_STATE_INPUT_REQUIRED,
                "INPUT_REQUIRED", null, "请选择城市", null);
        RecordingObserver observer = new RecordingObserver();
        executor(caller, properties()).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(observer.completed).isFalse();
        assertThat(observer.error).isNotNull();
        assertThat(String.valueOf(observer.chunks.get(observer.chunks.size() - 1).getData()))
                .contains("VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED");
    }

    @Test
    void targetResolutionFailureProducesTypedError() {
        FakeCaller caller = new FakeCaller();
        ControllerHandoffProperties p = properties();
        p.getTarget().setAllowedAgents(List.of("agent_card_l1")); // hotel 不在范围
        RecordingObserver observer = new RecordingObserver();
        executor(caller, p).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(caller.lastCall).isNull(); // 未发起调用
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED");
        assertThat(observer.error).isNotNull();
    }

    @Test
    void callerFailureMapsToTargetUnavailable() {
        FakeCaller caller = new FakeCaller();
        caller.failure = new RuntimeException("gateway 502");
        RecordingObserver observer = new RecordingObserver();
        executor(caller, properties()).execute(handoff(), request(), observer, new RequestHandoffState());
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_TARGET_UNAVAILABLE");
    }

    @Test
    void duplicateMessageSkipsSecondCallWithoutError() {
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        RequestHandoffState state = new RequestHandoffState();
        executor(caller, properties()).execute(handoff(), request(), observer, state);
        executor(caller, properties()).execute(handoff(), request(), observer, state);
        assertThat(caller.calls).isEqualTo(1); // 相同去重键只调用一次
        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue(); // 第一次调用已驱动到终态，第二次静默跳过
    }

    @Test
    void loopLimitProducesTypedError() {
        FakeCaller caller = new FakeCaller();
        ControllerHandoffProperties p = properties();
        p.getLoop().setMaxRedirects(1);
        RecordingObserver observer = new RecordingObserver();
        RequestHandoffState state = new RequestHandoffState();
        IntentHandoff first = new IntentHandoff("T", "intent_hotel", "hotel", "agent_card_hotel", "k1", "{}");
        IntentHandoff second = new IntentHandoff("T", "intent_hotel", "hotel", "agent_card_l1", "k2", "{}");
        executor(caller, p).execute(first, request(), observer, state);
        RecordingObserver secondObserver = new RecordingObserver();
        executor(caller, p).execute(second, request(), secondObserver, state);
        assertThat(String.valueOf(secondObserver.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_LOOP_LIMIT");
    }
}
