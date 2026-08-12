/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentInstanceManager}.
 *
 * @since 0.1.0
 */
class AgentInstanceManagerTest {

    @Test
    void acquire_callsFactoryCreate() {
        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create()).thenReturn(new Object());
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.acquire("conv-1");
        verify(factory).create();
    }

    @Test
    void acquire_returnsNewInstance_eachTime() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent1 = new Object();
        Object agent2 = new Object();
        when(factory.create()).thenReturn(agent1, agent2);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        Object result1 = manager.acquire("conv-1");
        Object result2 = manager.acquire("conv-1");
        assertThat(result1).isNotSameAs(result2);
    }

    @Test
    void release_removesFromActiveAgents() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent = new Object();
        when(factory.create()).thenReturn(agent);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.acquire("conv-1");
        manager.release("conv-1", agent);
        verify(factory).destroy(agent);
    }

    @Test
    void factoryCreateThrows_releasesNoAgent() {
        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create()).thenThrow(new IllegalStateException("create failed"));
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        assertThatThrownBy(() -> manager.acquire("conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("create failed");
    }

    @Test
    void release_callsFactoryDestroy() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent = new Object();
        when(factory.create()).thenReturn(agent);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        Object acquired = manager.acquire("conv-1");
        manager.release("conv-1", acquired);
        verify(factory).destroy(agent);
    }

    @Test
    void acquire_sameConversation_returnsNewInstance() {
        SpyAgentFactory factory = new SpyAgentFactory();
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        Object result1 = manager.acquire("conv-1");
        Object result2 = manager.acquire("conv-1");
        assertThat(result1).isNotSameAs(result2);
        assertThat(factory.createCount.get()).isEqualTo(2);
    }

    static class SpyAgentFactory implements AgentFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final AtomicInteger destroyCount = new AtomicInteger();
        final List<Object> created = new ArrayList<>();

        @Override
        public Object create() {
            Object agent = new Object();
            createCount.incrementAndGet();
            created.add(agent);
            return agent;
        }

        @Override
        public void destroy(Object agent) {
            destroyCount.incrementAndGet();
        }
    }
}
