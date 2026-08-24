/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.edp.handler.EdpaExtHandler.InitResult;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concurrency tests for {@link EdpAgentFactory} (DFX-002 §7.4).
 *
 * <p>Verifies that the V1 {@code ReentrantLock} serialization in
 * {@code create()} correctly prevents concurrent execution of the
 * critical section.
 *
 * <p>Uses a test subclass that overrides {@code createAgent()} to avoid
 * {@code MockedStatic}, which is thread-local and invisible to worker threads.
 *
 * @since 0.1.2
 */
class EdpAgentFactoryConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(EdpAgentFactoryConcurrencyTest.class);

    private static final int THREAD_COUNT = 10;

    private static void preserveInterrupt() {
        Thread.currentThread().interrupt();
    }

    private static InitResult createInitResult(AgentCard card, DeepAgentConfig config) {
        DeepAgent mockDeepAgent = mock(DeepAgent.class);
        when(mockDeepAgent.getCard()).thenReturn(card);
        when(mockDeepAgent.getConfig()).thenReturn(config);
        InitResult initResult = new InitResult();
        initResult.setDeepAgent(mockDeepAgent);
        return initResult;
    }

    /**
     * Testable factory that overrides createAgent() to track concurrency
     * without relying on MockedStatic.
     */
    private static class TestableFactory extends EdpAgentFactory {
        final AtomicInteger concurrentCount = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final AtomicInteger totalCalls = new AtomicInteger(0);
        volatile long sleepMs = 0L;
        volatile boolean throwOnFirst = false;
        final AtomicInteger callCount = new AtomicInteger(0);

        TestableFactory(InitResult initResult) {
            super(initResult);
        }

        @Override
        protected DeepAgent createAgent() {
            int count = totalCalls.incrementAndGet();
            if (throwOnFirst && callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated creation failure");
            }

            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
            try {
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException e) {
                preserveInterrupt(); // preserve interrupt status in test subclass
            } finally {
                concurrentCount.decrementAndGet();
            }
            return mock(DeepAgent.class);
        }
    }

    @Test
    void concurrentCreate_allSucceedWithDistinctInstances() throws Exception {
        AgentCard card = AgentCard.builder().id("concurrent-agent").name("Concurrent Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        TestableFactory factory = new TestableFactory(createInitResult(card, config));

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        Set<Integer> identityHashCodes = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicReference<Exception> firstError = new AtomicReference<>();

        ExecutorService exec = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setUncaughtExceptionHandler((thr, e) -> log.error("Uncaught: {}", e.getMessage()));
                    return t;
                });
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        Object agent = factory.create();
                        identityHashCodes.add(System.identityHashCode(agent));
                    } catch (Exception t) {
                        // capture any error from worker thread — test asserts firstError is null
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("all threads should complete within timeout").isTrue();
            assertThat(firstError.get())
                    .as("no thread should encounter an exception").isNull();
            assertThat(identityHashCodes)
                    .as("each thread should receive a distinct agent instance")
                    .hasSize(THREAD_COUNT);
            assertThat(factory.totalCalls.get()).isEqualTo(THREAD_COUNT);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void concurrentCreate_serializesCriticalSection() throws Exception {
        AgentCard card = AgentCard.builder().id("serial-agent").name("Serial Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        TestableFactory factory = new TestableFactory(createInitResult(card, config));
        factory.sleepMs = 20; // widen the race window

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        ExecutorService exec = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setUncaughtExceptionHandler((thr, e) -> log.error("Uncaught: {}", e.getMessage()));
                    return t;
                });
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        factory.create();
                    } catch (Exception ignored) {
                        // expected in concurrent test scenarios
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("all threads should complete within timeout").isTrue();
            assertThat(factory.maxConcurrent.get())
                    .as("V1 lock should serialize: max concurrent inside createAgent() must be 1")
                    .isEqualTo(1);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void concurrentCreate_exceptionReleasesLock() {
        AgentCard card = AgentCard.builder().id("error-agent").name("Error Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        TestableFactory factory = new TestableFactory(createInitResult(card, config));
        factory.throwOnFirst = true;

        // First call throws — lock must be released via finally
        try {
            factory.create();
        } catch (RuntimeException expected) {
            // expected — verifies exception path releases the lock
        }

        // Second call must succeed — proves lock was released after exception
        Object result = factory.create();
        assertThat(result).isNotNull();
        assertThat(factory.callCount.get()).isEqualTo(2);
    }

    @Test
    void concurrentCreate_destroyRunsOutsideLock() throws Exception {
        AgentCard card = AgentCard.builder().id("destroy-concurrent").name("Destroy Concurrent").build();
        DeepAgentConfig config = new DeepAgentConfig();

        AtomicReference<Boolean> overlapped = new AtomicReference<>(false);

        TestableFactory factory = new TestableFactory(createInitResult(card, config)) {
            @Override
            protected DeepAgent createAgent() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    preserveInterrupt(); // preserve interrupt status in test override
                }
                return mock(DeepAgent.class);
            }
        };

        DeepAgent mockAgent = mock(DeepAgent.class);

        // Start create() in background — holds the lock for ~100ms
        Thread createThread = new Thread(() -> factory.create(), "create-thread");
        createThread.setUncaughtExceptionHandler(
                (t, e) -> log.error("Uncaught exception in {}: {}", t.getName(), e.getMessage()));
        createThread.start();

        // Wait briefly for create() to acquire the lock
        Thread.sleep(20);

        // destroy() should NOT block — it runs outside the creation lock
        long start = System.nanoTime();
        factory.destroy(mockAgent);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        createThread.join(5000);

        // destroy() completed quickly while create() was still holding the lock
        assertThat(elapsed)
                .as("destroy() should complete without waiting for the creation lock (elapsed=%d ms)", elapsed)
                .isLessThan(80);
    }
}
