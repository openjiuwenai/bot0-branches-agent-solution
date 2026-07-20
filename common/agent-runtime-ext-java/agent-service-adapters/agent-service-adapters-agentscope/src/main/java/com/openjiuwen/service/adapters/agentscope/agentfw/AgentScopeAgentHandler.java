/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transparent bridge from the OpenJiuwen runtime SPI to a local AgentScope Java agent.
 *
 * @since 2026-07-20
 */
public final class AgentScopeAgentHandler implements AgentHandler, AgentInterruptHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentHandler.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final long CANCEL_POLL_MILLIS = 50L;

    private final AgentScopeInvoker invoker;
    private final Duration queryTimeout;
    private final Duration streamTimeout;
    private final AgentScopeRequestMapper requestMapper = new AgentScopeRequestMapper();
    private final AgentScopeResumeMapper resumeMapper = new AgentScopeResumeMapper();
    private final AgentScopeEventMapper eventMapper = new AgentScopeEventMapper();
    private final ConcurrentHashMap<String, ActiveInvocation> inFlight = new ConcurrentHashMap<>();

    AgentScopeAgentHandler(AgentScopeInvoker invoker, Duration queryTimeout, Duration streamTimeout) {
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.queryTimeout = positive(queryTimeout, "queryTimeout");
        this.streamTimeout = positive(streamTimeout, "streamTimeout");
    }

    /**
     * Creates a handler backed by a local {@link ReActAgent}.
     *
     * @param agent configured ReAct agent
     * @return AgentScope handler
     */
    public static AgentScopeAgentHandler forReActAgent(ReActAgent agent) {
        return new AgentScopeAgentHandler(new ReActAgentScopeInvoker(agent), QUERY_TIMEOUT, STREAM_TIMEOUT);
    }

    /**
     * Creates a handler backed by a local {@link HarnessAgent}.
     *
     * @param agent configured Harness agent
     * @return AgentScope handler
     */
    public static AgentScopeAgentHandler forHarnessAgent(HarnessAgent agent) {
        return new AgentScopeAgentHandler(new HarnessAgentScopeInvoker(agent), QUERY_TIMEOUT, STREAM_TIMEOUT);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        RuntimeContext context = requestMapper.mapContext(request);
        ActiveInvocation invocation = register(request.getConversationId(), context);
        AtomicReference<Msg> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            List<Msg> messages = resolveMessages(request, context);
            Disposable subscription = invoker.call(messages, context)
                .timeout(queryTimeout)
                .subscribe(
                    result::set,
                    error -> {
                        failure.set(error);
                        try {
                            if (isTimeout(error)) {
                                invocation.interruptAgent();
                            }
                        } finally {
                            invocation.finish();
                        }
                    },
                    invocation::finish);
            invocation.bind(subscription);
            invocation.await();
            if (invocation.isCancelled()) {
                throw new CancellationException("AgentScope query cancelled");
            }
            Throwable error = failure.get();
            if (error != null) {
                if (isTimeout(error)) {
                    throw new IllegalStateException("AgentScope query timed out", error);
                }
                throw new IllegalStateException("AgentScope query failed", error);
            }
            Msg message = result.get();
            if (message == null) {
                throw new IllegalStateException("AgentScope query completed without a result");
            }
            AgentState state = invoker.getAgentState(context.getUserId(), context.getSessionId());
            return new QueryResponse(eventMapper.mapResult(message, state), request.getConversationId());
        } finally {
            invocation.finish();
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        RuntimeContext context = requestMapper.mapContext(request);
        ActiveInvocation invocation = register(request.getConversationId(), context);
        AgentScopeEventMapper.StreamState streamState = new AgentScopeEventMapper.StreamState();
        try {
            List<Msg> messages = resolveMessages(request, context);
            Disposable subscription = subscribe(messages, context, invocation, observer, streamState);
            invocation.bind(subscription);
            awaitStream(invocation, observer);
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (invocation.finish() && !observer.isCancelled()) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, Map.of("message", "AgentScope stream failed")));
                observer.onError(error);
            } else {
                throw error;
            }
        } finally {
            invocation.finish();
        }
    }

    private Disposable subscribe(List<Msg> messages, RuntimeContext context, ActiveInvocation invocation,
        QueryStreamObserver observer, AgentScopeEventMapper.StreamState streamState) {
        return invoker.streamEvents(messages, context)
            .timeout(streamTimeout)
            .subscribe(
                event -> handleStreamEvent(event, context, invocation, observer, streamState),
                error -> handleStreamError(invocation, observer, error),
                () -> handleStreamComplete(invocation, observer, streamState));
    }

    private void handleStreamEvent(AgentEvent event, RuntimeContext context, ActiveInvocation invocation,
        QueryStreamObserver observer, AgentScopeEventMapper.StreamState streamState) {
        if (invocation.isClosed() || observer.isCancelled()) {
            if (observer.isCancelled()) {
                invocation.cancel();
            }
            return;
        }
        eventMapper.map(
            event,
            () -> invoker.getAgentState(context.getUserId(), context.getSessionId()),
            streamState).ifPresent(observer::onNext);
    }

    private void handleStreamComplete(ActiveInvocation invocation, QueryStreamObserver observer,
        AgentScopeEventMapper.StreamState streamState) {
        if (!invocation.finish() || observer.isCancelled()) {
            return;
        }
        if (!streamState.hasTerminalEvent()) {
            IllegalStateException error = new IllegalStateException(
                "AgentScope stream completed without a terminal event");
            observer.onNext(new QueryChunk(
                QueryChunk.TYPE_ERROR,
                Map.of("message", "AgentScope stream completed without a terminal event")));
            observer.onError(error);
            return;
        }
        observer.onComplete();
    }

    private static void awaitStream(ActiveInvocation invocation, QueryStreamObserver observer) {
        while (!invocation.await(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (observer.isCancelled()) {
                invocation.cancel();
            }
        }
    }

    @Override
    public void interrupt(String conversationId, InterruptReason reason) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        ActiveInvocation invocation = inFlight.get(conversationId);
        if (invocation != null) {
            invocation.cancel();
        }
    }

    private List<Msg> resolveMessages(ServeRequest request, RuntimeContext context) {
        if (!resumeMapper.hasResumeInteraction(request)) {
            return requestMapper.mapCurrentTurn(request);
        }
        AgentState state = invoker.getAgentState(context.getUserId(), context.getSessionId());
        try {
            return resumeMapper.map(request, state);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Invalid AgentScope resume input", error);
        }
    }

    private void handleStreamError(ActiveInvocation invocation, QueryStreamObserver observer, Throwable error) {
        try {
            if (isTimeout(error)) {
                invocation.interruptAgent();
            }
        } finally {
            finishStreamError(invocation, observer, error);
        }
    }

    private static void finishStreamError(ActiveInvocation invocation, QueryStreamObserver observer,
        Throwable error) {
        if (!invocation.finish() || observer.isCancelled()) {
            return;
        }
        String message = isTimeout(error) ? "AgentScope stream timed out" : "AgentScope stream failed";
        observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, Map.of("message", message)));
        observer.onError(error);
    }

    private ActiveInvocation register(String conversationId, RuntimeContext context) {
        ActiveInvocation invocation = new ActiveInvocation(conversationId, context.getUserId(), context.getSessionId());
        if (inFlight.putIfAbsent(conversationId, invocation) != null) {
            throw new IllegalStateException(
                "AgentScope invocation already in progress for conversationId=" + conversationId);
        }
        return invocation;
    }

    private void remove(ActiveInvocation invocation) {
        inFlight.remove(invocation.conversationId, invocation);
    }

    private static Duration positive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static boolean isTimeout(Throwable error) {
        return error instanceof TimeoutException;
    }

    private final class ActiveInvocation {
        private final String conversationId;
        private final String userId;
        private final String sessionId;
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CountDownLatch done = new CountDownLatch(1);

        private ActiveInvocation(String conversationId, String userId, String sessionId) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.sessionId = sessionId;
        }

        private void bind(Disposable disposable) {
            Objects.requireNonNull(disposable, "subscription must not be null");
            if (!subscription.compareAndSet(null, disposable)) {
                disposable.dispose();
                throw new IllegalStateException("subscription already bound");
            }
            if (closed.get()) {
                disposable.dispose();
            }
        }

        private boolean finish() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            remove(this);
            done.countDown();
            return true;
        }

        private void cancel() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelled.set(true);
            try {
                interruptAgent();
            } finally {
                Disposable disposable = subscription.get();
                if (disposable != null) {
                    disposable.dispose();
                }
                remove(this);
                done.countDown();
            }
        }

        private void interruptAgent() {
            try {
                invoker.interrupt(userId, sessionId);
            } catch (IllegalArgumentException | IllegalStateException error) {
                log.warn("AgentScope session interrupt failed conversationId={}", conversationId, error);
            }
        }

        private void await() {
            try {
                done.await();
            } catch (InterruptedException error) {
                cancel();
                throw interrupted("Interrupted while waiting for AgentScope", error);
            }
        }

        private boolean await(long timeout, TimeUnit unit) {
            try {
                return done.await(timeout, unit);
            } catch (InterruptedException error) {
                cancel();
                throw interrupted("Interrupted while waiting for AgentScope stream", error);
            }
        }

        private CancellationException interrupted(String message, InterruptedException cause) {
            CancellationException cancellation = new CancellationException(message);
            cancellation.initCause(cause);
            return cancellation;
        }

        private boolean isClosed() {
            return closed.get();
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
