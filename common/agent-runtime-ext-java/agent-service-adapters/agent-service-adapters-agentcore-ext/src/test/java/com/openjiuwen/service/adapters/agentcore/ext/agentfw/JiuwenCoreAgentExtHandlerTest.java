/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

        try {
            handler.query(request("c-except-q", "fail"));
        } catch (Exception expected) {
            // Expected — agent.invoke() throws
        }

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

    public static class IdentityStreamAgent {
        private final String identity;

        IdentityStreamAgent(String identity) {
            this.identity = identity;
        }

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
        }
    }

    public static class IdentityInvokeAgent {
        private final String identity;

        IdentityInvokeAgent(String identity) {
            this.identity = identity;
        }

        public Object invoke(Object inputs, Session session) {
            return Map.of("output", identity, "result_type", "answer");
        }

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
        }
    }

    public static class ThrowingStreamAgent {
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            throw new RuntimeException("stream failed");
        }
    }

    public static class ThrowingInvokeAgent {
        public Object invoke(Object inputs, Session session) {
            throw new RuntimeException("invoke failed");
        }

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            throw new RuntimeException("stream failed");
        }
    }
}
