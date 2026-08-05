/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests local AgentScope handler execution, streaming, resume, and cancellation behavior.
 *
 * @since 2026-07-20
 */
class AgentScopeAgentHandlerTest {
    @Test
    void queryMapsNormalResult() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ServeRequest request = request("conversation", "hello");
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("done")
            .generateReason(GenerateReason.MODEL_STOP).build();
        when(invoker.call(any(), any())).thenReturn(Mono.just(result));
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith());
        AgentScopeAgentHandler handler = handler(invoker);

        QueryResponse response = handler.query(request);

        assertThat(response.getConversationId()).isEqualTo("conversation");
        assertThat(response.getResult()).isEqualTo(Map.of("role", "assistant", "content", "done"));
    }

    @Test
    void a2aResumeActionDoesNotReachAgentScopeAsOrdinaryText() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ToolUseBlock tool = tool("call-1", ToolCallState.ASKING);
        ServeRequest request = request("conversation", "APPROVE");
        request.getMetadata().put("_interrupt", Map.of("payload", Map.of("kind", "confirmation")));
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith(tool));
        when(invoker.call(any(), any())).thenReturn(Mono.just(
            Msg.builder().role(MsgRole.ASSISTANT).textContent("approved")
                .generateReason(GenerateReason.MODEL_STOP).build()));
        AgentScopeAgentHandler handler = handler(invoker);

        handler.query(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(invoker).call(messages.capture(), any());
        assertThat(messages.getValue()).singleElement().satisfies(message -> {
            assertThat(message.getTextContent()).isEmpty();
            assertThat(message.getMetadata()).containsKey(Msg.METADATA_CONFIRM_RESULTS);
        });
    }

    @Test
    void approveTextInANewRequestRemainsOrdinaryUserContent() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ServeRequest request = request("conversation", "APPROVE");
        when(invoker.call(any(), any())).thenReturn(Mono.just(
            Msg.builder().role(MsgRole.ASSISTANT).textContent("ordinary")
                .generateReason(GenerateReason.MODEL_STOP).build()));
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith());

        handler(invoker).query(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(invoker).call(messages.capture(), any());
        assertThat(messages.getValue()).singleElement().satisfies(message -> {
            assertThat(message.getTextContent()).isEqualTo("APPROVE");
            assertThat(message.getMetadata()).doesNotContainKey(Msg.METADATA_CONFIRM_RESULTS);
        });
    }

    @Test
    void invalidA2aResumeInputIsReportedAsAgentExecutionFailure() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ToolUseBlock tool = tool("call-1", ToolCallState.ASKING);
        ServeRequest request = request("conversation", "confirm");
        request.getMetadata().put("_interrupt", Map.of("payload", Map.of("kind", "confirmation")));
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith(tool));

        assertThatThrownBy(() -> handler(invoker).query(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resume input");
        verify(invoker, times(0)).call(any(), any());
    }

    @Test
    void streamMapsDeltaAndSingleConfirmationInterrupt() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ToolUseBlock tool = tool("call-1", ToolCallState.ASKING);
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith(tool));
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.just(
            new TextBlockDeltaEvent("reply", "block", "hello"),
            new RequireUserConfirmEvent("reply", List.of(tool))));
        RecordingObserver observer = new RecordingObserver();

        handler(invoker).streamQuery(request("conversation", "transfer"), observer);

        assertThat(observer.chunks).extracting(QueryChunk::getType)
            .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_INTERRUPT);
        assertThat(observer.completed).isEqualTo(1);
        assertThat(observer.errors).isEmpty();
    }

    @Test
    void streamReportsErrorWhenTextDeltaIsNotFollowedByTerminalEvent() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.just(
            new TextBlockDeltaEvent("reply", "block", "hello")));
        RecordingObserver observer = new RecordingObserver();

        handler(invoker).streamQuery(request("conversation", "hello"), observer);

        verify(invoker, times(0)).getAgentState(any(), any());
        assertThat(observer.chunks).extracting(QueryChunk::getType)
            .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_ERROR);
        assertThat(observer.errors).singleElement().satisfies(error -> assertThat(error)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a terminal event"));
        assertThat(observer.completed).isZero();
    }

    @Test
    void streamReportsErrorWhenOnlyUnrelatedEventsAreEmitted() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.just(
            new AgentStartEvent("conversation", "reply", "agent")));
        RecordingObserver observer = new RecordingObserver();

        handler(invoker).streamQuery(request("conversation", "hello"), observer);

        verify(invoker, times(0)).getAgentState(any(), any());
        assertThat(observer.chunks).singleElement().satisfies(chunk ->
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_ERROR));
        assertThat(observer.errors).singleElement().satisfies(error -> assertThat(error)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a terminal event"));
        assertThat(observer.completed).isZero();
    }

    @Test
    void streamWaitsForObserverCompletion() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        ToolUseBlock tool = tool("call-1", ToolCallState.ASKING);
        when(invoker.getAgentState("user", "conversation")).thenReturn(stateWith(tool));
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.<AgentEvent>just(
            new RequireUserConfirmEvent("reply", List.of(tool))).subscribeOn(Schedulers.boundedElastic()));
        BlockingCompletionObserver observer = new BlockingCompletionObserver();
        ExecutorService executor = newSingleTaskExecutor();
        try {
            Future<?> stream = executor.submit(() ->
                handler(invoker).streamQuery(request("conversation", "transfer"), observer));
            assertThat(observer.completionEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> stream.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            observer.allowCompletion.countDown();
            stream.get(1, TimeUnit.SECONDS);
        } finally {
            observer.allowCompletion.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void streamReportsSynchronousArgumentFailureThroughObserver() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        IllegalArgumentException failure = new IllegalArgumentException("invalid stream input");
        when(invoker.streamEvents(any(), any())).thenThrow(failure);
        RecordingObserver observer = new RecordingObserver();

        handler(invoker).streamQuery(request("conversation", "hello"), observer);

        assertThat(observer.chunks).singleElement().satisfies(chunk ->
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_ERROR));
        assertThat(observer.errors).containsExactly(failure);
        assertThat(observer.completed).isZero();
    }

    @Test
    void queryTimeoutInterruptsAgentScopeSession() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.call(any(), any())).thenReturn(Mono.never());
        AgentScopeAgentHandler handler = new AgentScopeAgentHandler(
            invoker, Duration.ofMillis(30), Duration.ofSeconds(1));

        assertThatThrownBy(() -> handler.query(request("conversation", "wait")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("timed out");
        verify(invoker, atLeastOnce()).interrupt("user", "conversation");
    }

    @Test
    void queryTimeoutCompletesWhenAgentInterruptFails() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.call(any(), any())).thenReturn(Mono.never());
        doThrow(new UnsupportedOperationException("interrupt failed"))
            .when(invoker).interrupt("user", "conversation");
        AgentScopeAgentHandler handler = new AgentScopeAgentHandler(
            invoker, Duration.ofMillis(30), Duration.ofSeconds(1));
        CompletableFuture<IllegalStateException> outcome = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        IllegalStateException notCompleted = new IllegalStateException("query did not complete");

        IllegalStateException failure = outcome.completeOnTimeout(notCompleted, 500, TimeUnit.MILLISECONDS).join();

        if (failure == notCompleted) {
            assertThatThrownBy(() -> handler.interrupt("conversation", InterruptReason.USER_REQUEST))
                .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(failure).hasMessageContaining("timed out");
    }

    @Test
    void streamTimeoutInterruptsAgentScopeSessionAndReportsOneError() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.never());
        AgentScopeAgentHandler handler = new AgentScopeAgentHandler(
            invoker, Duration.ofSeconds(1), Duration.ofMillis(30));
        RecordingObserver observer = new RecordingObserver();

        handler.streamQuery(request("conversation", "wait"), observer);

        verify(invoker, atLeastOnce()).interrupt("user", "conversation");
        assertThat(observer.chunks).singleElement().satisfies(chunk ->
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_ERROR));
        assertThat(observer.errors).hasSize(1);
        assertThat(observer.completed).isZero();
    }

    @Test
    void streamTimeoutCompletesWhenAgentInterruptFails() {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        when(invoker.streamEvents(any(), any())).thenReturn(Flux.never());
        doThrow(new UnsupportedOperationException("interrupt failed"))
            .when(invoker).interrupt("user", "conversation");
        AgentScopeAgentHandler handler = new AgentScopeAgentHandler(
            invoker, Duration.ofSeconds(1), Duration.ofMillis(30));
        RecordingObserver observer = new RecordingObserver();
        CompletableFuture<Boolean> outcome = CompletableFuture.supplyAsync(() -> {
            handler.streamQuery(request("conversation", "wait"), observer);
            return true;
        });

        boolean completed = outcome.completeOnTimeout(false, 500, TimeUnit.MILLISECONDS).join();

        if (!completed) {
            assertThatThrownBy(() -> handler.interrupt("conversation", InterruptReason.USER_REQUEST))
                .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(completed).isTrue();
        assertThat(observer.chunks).singleElement().satisfies(chunk ->
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_ERROR));
        assertThat(observer.errors).hasSize(1);
    }

    @Test
    void lifecycleInterruptCancelsAndWakesInFlightQuery() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        CountDownLatch subscribed = new CountDownLatch(1);
        when(invoker.call(any(), any())).thenReturn(Mono.create(sink -> subscribed.countDown()));
        AgentScopeAgentHandler handler = handler(invoker);
        CompletableFuture<IllegalStateException> outcome = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();

        handler.interrupt("conversation", InterruptReason.USER_REQUEST);

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        verify(invoker, timeout(1000)).interrupt("user", "conversation");
    }

    @Test
    void threadInterruptCancelsQueryAndPreservesCause() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(invoker.call(any(), any())).thenReturn(Mono.create(sink -> subscribed.countDown()));
        AgentScopeAgentHandler handler = handler(invoker);
        AtomicReference<IllegalStateException> failure = new AtomicReference<>();
        ExecutorService executor = newSingleTaskExecutor();
        try {
            Future<?> query = executor.submit(() -> {
                try {
                    handler.query(request("conversation", "wait"));
                } catch (IllegalStateException error) {
                    failure.set(error);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();

            query.cancel(true);

            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get())
                .isInstanceOf(CancellationException.class)
                .hasCauseInstanceOf(InterruptedException.class);
            verify(invoker, timeout(1000)).interrupt("user", "conversation");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptFailureStillReleasesWaitingQuery() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        CountDownLatch subscribed = new CountDownLatch(1);
        when(invoker.call(any(), any())).thenReturn(Mono.create(sink -> subscribed.countDown()));
        doThrow(new UnsupportedOperationException("interrupt failed"))
            .when(invoker).interrupt("user", "conversation");
        AgentScopeAgentHandler handler = handler(invoker);
        CompletableFuture<IllegalStateException> outcome = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> handler.interrupt("conversation", InterruptReason.USER_REQUEST))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    }

    @Test
    void cancellationBeforeSubscriptionBindingDisposesLateSubscription() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        CountDownLatch callEntered = new CountDownLatch(1);
        CountDownLatch returnPublisher = new CountDownLatch(1);
        CountDownLatch publisherCancelled = new CountDownLatch(1);
        when(invoker.call(any(), any())).thenAnswer(invocation -> {
            callEntered.countDown();
            assertThat(returnPublisher.await(1, TimeUnit.SECONDS)).isTrue();
            return Mono.<Msg>never().doOnCancel(publisherCancelled::countDown);
        });
        AgentScopeAgentHandler handler = handler(invoker);
        CompletableFuture<IllegalStateException> outcome = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        assertThat(callEntered.await(1, TimeUnit.SECONDS)).isTrue();

        handler.interrupt("conversation", InterruptReason.USER_REQUEST);
        returnPublisher.countDown();

        assertThat(outcome.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(publisherCancelled.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsSecondConcurrentInvocationForSameConversation() throws Exception {
        AgentScopeInvoker invoker = mock(AgentScopeInvoker.class);
        CountDownLatch subscribed = new CountDownLatch(1);
        when(invoker.call(any(), any())).thenReturn(
            Mono.<Msg>never().doOnSubscribe(ignored -> subscribed.countDown()));
        AgentScopeAgentHandler handler = handler(invoker);
        CompletableFuture<IllegalStateException> first = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<IllegalStateException> second = CompletableFuture.supplyAsync(() -> queryFailure(handler));
        IllegalStateException notRejected = new IllegalStateException("second invocation was not rejected");
        IllegalStateException secondOutcome = second.completeOnTimeout(notRejected, 500, TimeUnit.MILLISECONDS).join();

        try {
            assertThat(secondOutcome)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
            verify(invoker, times(1)).call(any(), any());
        } finally {
            handler.interrupt("conversation", InterruptReason.USER_REQUEST);
        }

        assertThat(first.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        verify(invoker, timeout(1000)).interrupt("user", "conversation");
    }

    private static AgentScopeAgentHandler handler(AgentScopeInvoker invoker) {
        return new AgentScopeAgentHandler(invoker, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private static ExecutorService newSingleTaskExecutor() {
        return new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static IllegalStateException queryFailure(AgentScopeAgentHandler handler) {
        try {
            handler.query(request("conversation", "wait"));
        } catch (IllegalStateException error) {
            return error;
        }
        throw new AssertionError("AgentScope query completed without the expected failure");
    }

    private static ServeRequest request(String conversationId, String text) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setUserId("user");
        request.setMessages(List.of(Map.of("role", "user", "content", text)));
        return request;
    }

    private static ToolUseBlock tool(String id, ToolCallState state) {
        return ToolUseBlock.builder().id(id).name("transfer").input(Map.of()).state(state).build();
    }

    private static AgentState stateWith(ToolUseBlock... tools) {
        AgentState.Builder builder = AgentState.builder().userId("user").sessionId("conversation");
        if (tools.length > 0) {
            builder.addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(tools).build());
        }
        return builder.build();
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

    private static final class BlockingCompletionObserver implements QueryStreamObserver {
        private final CountDownLatch completionEntered = new CountDownLatch(1);
        private final CountDownLatch allowCompletion = new CountDownLatch(1);

        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError("Unexpected stream error", error);
        }

        @Override
        public void onComplete() {
            completionEntered.countDown();
            try {
                if (!allowCompletion.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Completion observer was not released");
                }
            } catch (InterruptedException error) {
                throw new AssertionError("Completion observer was interrupted", error);
            }
        }
    }
}
