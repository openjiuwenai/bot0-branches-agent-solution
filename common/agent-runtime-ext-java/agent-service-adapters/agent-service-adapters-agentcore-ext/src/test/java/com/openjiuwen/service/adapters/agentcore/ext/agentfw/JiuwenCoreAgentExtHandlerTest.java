/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.ConversationBusyException;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskQuotaTracker;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit tests for {@link JiuwenCoreAgentExtHandler} per-Task Agent lifecycle
 * and quota tracker integration (DFX-002 U-46~U-53). Admission quota release
 * is owned by {@code A2AAgentExecutor} in the runtime module — the handler no
 * longer touches the admission gate.
 *
 * @since 0.1.2
 */
class JiuwenCoreAgentExtHandlerTest {
    @Test
    @SuppressWarnings("unchecked")
    void streamQuery_usesPerTaskAgent_notSingleton() {
        IdentityStreamAgent singletonAgent = new IdentityStreamAgent("singleton");
        IdentityStreamAgent perTaskAgent = new IdentityStreamAgent("per-task");

        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        when(agentManager.acquire("c-per-task")).thenReturn(perTaskAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(singletonAgent);
        handler.setAgentManager(agentManager);

        List<QueryChunk> chunks = new ArrayList<>();
        handler.streamQuery(request("c-per-task", "hello"), collectingObserver(chunks));

        assertThat(chunks).hasSize(1);
        Map<String, Object> data = (Map<String, Object>) chunks.get(0).getData();
        assertThat(data.get("payload")).isEqualTo(Map.of("content", "per-task"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void query_usesPerTaskAgent_notSingleton() {
        IdentityInvokeAgent singletonAgent = new IdentityInvokeAgent("singleton");
        IdentityInvokeAgent perTaskAgent = new IdentityInvokeAgent("per-task");

        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        when(agentManager.acquire("c-per-task-invoke")).thenReturn(perTaskAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(singletonAgent);
        handler.setAgentManager(agentManager);

        QueryResponse response = handler.query(request("c-per-task-invoke", "hello"));

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "per-task");
    }

    @Test
    void streamQuery_exception_finally_releasesAll() {
        TaskQuotaTracker quotaTracker = mock(TaskQuotaTracker.class);
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        ThrowingStreamAgent throwingAgent = new ThrowingStreamAgent();
        when(agentManager.acquire("c-except")).thenReturn(throwingAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityStreamAgent("unused"));
        handler.setQuotaTracker(quotaTracker);
        handler.setAgentManager(agentManager);

        handler.streamQuery(request("c-except", "fail"), collectingObserver(new ArrayList<>()));

        verify(quotaTracker).onTaskReleased("c-except");
        verify(agentManager).release("c-except", throwingAgent);
    }

    @Test
    void query_exception_finally_releasesAll() {
        TaskQuotaTracker quotaTracker = mock(TaskQuotaTracker.class);
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        ThrowingInvokeAgent throwingAgent = new ThrowingInvokeAgent();
        when(agentManager.acquire("c-except-q")).thenReturn(throwingAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("unused"));
        handler.setQuotaTracker(quotaTracker);
        handler.setAgentManager(agentManager);

        catchThrowable(() -> handler.query(request("c-except-q", "fail")));

        verify(quotaTracker).onTaskReleased("c-except-q");
        verify(agentManager).release("c-except-q", throwingAgent);
    }

    @Test
    void streamQuery_callsQuotaTracker_onTaskWorking() {
        TaskQuotaTracker quotaTracker = mock(TaskQuotaTracker.class);
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityStreamAgent("ok"));
        handler.setQuotaTracker(quotaTracker);

        ServeRequest req = request("c-quota", "hello");
        req.setMetadata(Map.of("runtime.parentTaskId", "task-123"));

        handler.streamQuery(req, collectingObserver(new ArrayList<>()));

        verify(quotaTracker).onTaskWorking("c-quota", "task-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamQuery_finally_removesThreadLocal() {
        IdentityStreamAgent firstAgent = new IdentityStreamAgent("first");
        IdentityStreamAgent secondAgent = new IdentityStreamAgent("second");

        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        when(agentManager.acquire("c-threadlocal"))
                .thenReturn(firstAgent)
                .thenReturn(secondAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityStreamAgent("singleton"));
        handler.setAgentManager(agentManager);

        List<QueryChunk> chunks1 = new ArrayList<>();
        handler.streamQuery(request("c-threadlocal", "hello"), collectingObserver(chunks1));
        assertThat(handler.currentTaskAgent.get()).isNull();
        assertThat(((Map<String, Object>) chunks1.get(0).getData()).get("payload"))
                .isEqualTo(Map.of("content", "first"));

        List<QueryChunk> chunks2 = new ArrayList<>();
        handler.streamQuery(request("c-threadlocal", "hello"), collectingObserver(chunks2));
        assertThat(handler.currentTaskAgent.get()).isNull();
        assertThat(((Map<String, Object>) chunks2.get(0).getData()).get("payload"))
                .isEqualTo(Map.of("content", "second"));
    }

    @Test
    void streamQuery_conversationBusy_wrapsAsStructuredError() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        when(agentManager.acquire("c-busy"))
                .thenThrow(new ConversationBusyException("Conversation already has an active agent: c-busy"));

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityStreamAgent("unused"));
        handler.setAgentManager(agentManager);

        assertThatThrownBy(() -> handler.streamQuery(request("c-busy", "hello"),
                collectingObserver(new ArrayList<>())))
                .isInstanceOfSatisfying(AgentExecutionException.class, ex -> {
                    assertThat(ex.getMessage()).contains("Conversation busy");
                    assertThat(ex.getDescriptor().code()).isEqualTo("CONVERSATION_BUSY");
                    assertThat(ex.getDescriptor().isRetryable()).isTrue();
                    assertThat(ex.getCause()).isInstanceOf(ConversationBusyException.class);
                });
    }

    @Test
    void query_conversationBusy_wrapsAsStructuredError() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        when(agentManager.acquire("c-busy-q"))
                .thenThrow(new ConversationBusyException("Conversation already has an active agent: c-busy-q"));

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("unused"));
        handler.setAgentManager(agentManager);

        assertThatThrownBy(() -> handler.query(request("c-busy-q", "hello")))
                .isInstanceOfSatisfying(AgentExecutionException.class, ex -> {
                    assertThat(ex.getMessage()).contains("Conversation busy");
                    assertThat(ex.getDescriptor().code()).isEqualTo("CONVERSATION_BUSY");
                    assertThat(ex.getDescriptor().isRetryable()).isTrue();
                    assertThat(ex.getCause()).isInstanceOf(ConversationBusyException.class);
                });
    }

    @Test
    void prepareTask_cachesAgent_completeTaskReleasesIt() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        Object agent = new IdentityInvokeAgent("per-task");
        when(agentManager.acquire("c-lifecycle")).thenReturn(agent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("singleton"));
        handler.setAgentManager(agentManager);

        Optional<Object> token = handler.prepareTask(request("c-lifecycle", "hello"));
        assertThat(token).isPresent();

        QueryResponse response = handler.query(request("c-lifecycle", "hello"));
        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "per-task");
        // query() must NOT release the agent — it is owned by the task lifecycle now
        verify(agentManager, never()).release("c-lifecycle", agent);

        handler.completeTask(token);
        verify(agentManager).release("c-lifecycle", agent);
    }

    @Test
    void prepareTask_secondTaskWhileCached_throwsConversationBusy() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        Object firstAgent = new IdentityInvokeAgent("first");
        when(agentManager.acquire("c-conflict")).thenReturn(firstAgent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("singleton"));
        handler.setAgentManager(agentManager);

        Optional<Object> firstToken = handler.prepareTask(request("c-conflict", "hello"));

        // A second task for the same conversation while the first is in flight:
        // must fail with the retryable CONVERSATION_BUSY error, not silently
        // share the first task's agent.
        assertThatThrownBy(() -> handler.prepareTask(request("c-conflict", "second")))
                .isInstanceOfSatisfying(AgentExecutionException.class, ex -> {
                    assertThat(ex.getDescriptor().code()).isEqualTo("CONVERSATION_BUSY");
                    assertThat(ex.getDescriptor().isRetryable()).isTrue();
                });
        // The busy rejection must not have disturbed the first task's agent
        verify(agentManager, never()).release(org.mockito.ArgumentMatchers.eq("c-conflict"), any());

        // P0 regression: the rejected task's finally calls completeTask with an
        // empty Optional (it never acquired anything) — the first task's agent and
        // cache entry must survive untouched.
        handler.completeTask(Optional.empty());
        verify(agentManager, never()).release("c-conflict", firstAgent);

        // The first task still owns its agent and can complete normally
        handler.completeTask(firstToken);
        verify(agentManager).release("c-conflict", firstAgent);
    }

    @Test
    void prepareTask_afterCompleteTask_newTaskCanAcquire() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        Object first = new IdentityInvokeAgent("first");
        Object second = new IdentityInvokeAgent("second");
        when(agentManager.acquire("c-reuse")).thenReturn(first, second);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("singleton"));
        handler.setAgentManager(agentManager);

        Optional<Object> firstToken = handler.prepareTask(request("c-reuse", "hello"));
        handler.completeTask(firstToken);
        verify(agentManager).release("c-reuse", first);

        Optional<Object> secondToken = handler.prepareTask(request("c-reuse", "hello again"));
        QueryResponse response = handler.query(request("c-reuse", "hello again"));
        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "second");
        handler.completeTask(secondToken);
        verify(agentManager).release("c-reuse", second);
    }

    @Test
    void completeTask_nullOrForeignToken_neverReleasesOwnerAgent() {
        AgentInstanceManager agentManager = mock(AgentInstanceManager.class);
        Object agent = new IdentityInvokeAgent("owner");
        when(agentManager.acquire("c-owner")).thenReturn(agent);

        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new IdentityInvokeAgent("singleton"));
        handler.setAgentManager(agentManager);

        Optional<Object> token = handler.prepareTask(request("c-owner", "hello"));

        // empty Optional: nothing acquired for that caller — must be a no-op
        handler.completeTask(Optional.empty());
        // foreign token: belongs to nobody — must be a no-op
        handler.completeTask(Optional.of(new Object()));
        verify(agentManager, never()).release(org.mockito.ArgumentMatchers.eq("c-owner"), any());

        // the real owner's token still releases exactly once
        handler.completeTask(token);
        verify(agentManager).release("c-owner", agent);

        // a second completeTask with the now-stale token must not re-release
        handler.completeTask(token);
        verify(agentManager).release("c-owner", agent); // still exactly once
    }

    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    private static QueryStreamObserver collectingObserver(List<QueryChunk> chunks) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    /**
     * Test agent that returns its identity in a streaming response.
     */
    public static class IdentityStreamAgent {
        private final String identity;

        IdentityStreamAgent(String identity) {
            this.identity = identity;
        }

        /**
         * Returns a stream containing the agent's identity.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return an iterator over the stream output
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
        }
    }

    /**
     * Test agent that returns its identity in a synchronous response.
     */
    public static class IdentityInvokeAgent {
        private final String identity;

        IdentityInvokeAgent(String identity) {
            this.identity = identity;
        }

        /**
         * Returns a map containing the agent's identity.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @return a map containing the agent identity and result type
         */
        public Object invoke(Object inputs, Session session) {
            return Map.of("output", identity, "result_type", "answer");
        }

        /**
         * Returns a stream containing the agent's identity.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return an iterator over the stream output
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
        }
    }

    /**
     * Test agent that always throws on streaming invocation.
     */
    public static class ThrowingStreamAgent {
        /**
         * Always throws an {@link IllegalStateException}.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return never returns normally
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            throw new IllegalStateException("stream failed");
        }
    }

    /**
     * Test agent that always throws on synchronous invocation.
     */
    public static class ThrowingInvokeAgent {
        /**
         * Always throws an {@link IllegalStateException}.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @return never returns normally
         */
        public Object invoke(Object inputs, Session session) {
            throw new IllegalStateException("invoke failed");
        }

        /**
         * Always throws an {@link IllegalStateException}.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return never returns normally
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            throw new IllegalStateException("stream failed");
        }
    }
}
