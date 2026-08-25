/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void acquire_sameConversationConcurrent_rejectsSecond() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent1 = new Object();
        Object agent2 = new Object();
        when(factory.create()).thenReturn(agent1, agent2);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.acquire("conv-1");
        assertThatThrownBy(() -> manager.acquire("conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conv-1");
    }

    @Test
    void acquire_existingConversation_rejectsBeforeCreate() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent1 = new Object();
        when(factory.create()).thenReturn(agent1);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.acquire("conv-1");
        assertThatThrownBy(() -> manager.acquire("conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conv-1");
        verify(factory, times(1)).create();
        verify(factory, never()).destroy(any());
    }

    @Test
    void acquire_raceWindowDuringCreate_loserAgentDestroyed() throws Exception {
        AgentFactory factory = mock(AgentFactory.class);
        Object slowAgent = new Object();
        Object fastAgent = new Object();
        CountDownLatch enteredCreate = new CountDownLatch(1);
        CountDownLatch releaseSlowCreate = new CountDownLatch(1);
        when(factory.create()).thenAnswer(invocation -> {
            enteredCreate.countDown();
            releaseSlowCreate.await();
            return slowAgent;
        }).thenReturn(fastAgent);

        AgentInstanceManager manager = new AgentInstanceManager(factory);
        ExecutorService executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            Future<Object> slow = executor.submit(() -> manager.acquire("conv-1"));
            assertThat(enteredCreate.await(5, TimeUnit.SECONDS)).isTrue();

            Object winner = manager.acquire("conv-1");
            releaseSlowCreate.countDown();

            assertThatThrownBy(slow::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(winner).isSameAs(fastAgent);
            verify(factory).destroy(slowAgent);
            verify(factory, never()).destroy(fastAgent);
        } finally {
            releaseSlowCreate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void acquire_concurrentSameConversation_exactlyOneWinnerNoLeak() throws Exception {
        SpyAgentFactory factory = new SpyAgentFactory();
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        int threads = 8;
        ExecutorService executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return manager.acquire("conv-1");
                }));
            }
            start.countDown();

            int successes = 0;
            int rejections = 0;
            for (Future<Object> future : futures) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                    rejections++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(rejections).isEqualTo(threads - 1);
            assertThat(factory.createCount.get()).isEqualTo(factory.destroyCount.get() + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void release_removesFromActiveAgents() {
        AgentFactory factory = mock(AgentFactory.class);
        Object agent = new Object();
        Object recreated = new Object();
        when(factory.create()).thenReturn(agent, recreated);
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.acquire("conv-1");
        manager.release("conv-1", agent);
        verify(factory).destroy(agent);

        // The conversation is free again: a fresh acquire must succeed with a
        // new instance — proves the entry was really removed from activeAgents
        Object reacquired = manager.acquire("conv-1");
        assertThat(reacquired).isSameAs(recreated);
    }

    @Test
    void factoryCreateThrows_releasesNoAgent() {
        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create()).thenThrow(new IllegalStateException("create failed"));
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        assertThatThrownBy(() -> manager.acquire("conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("create failed");
        // No residue: the failed acquire must not destroy anything and must
        // not leave the conversation marked busy — a retry can succeed
        verify(factory, never()).destroy(any());
        org.mockito.Mockito.doReturn(new Object()).when(factory).create();
        assertThat(manager.acquire("conv-1")).isNotNull();
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
    void acquire_differentConversations_bothSucceed() {
        SpyAgentFactory factory = new SpyAgentFactory();
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        Object result1 = manager.acquire("conv-1");
        Object result2 = manager.acquire("conv-2");
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
