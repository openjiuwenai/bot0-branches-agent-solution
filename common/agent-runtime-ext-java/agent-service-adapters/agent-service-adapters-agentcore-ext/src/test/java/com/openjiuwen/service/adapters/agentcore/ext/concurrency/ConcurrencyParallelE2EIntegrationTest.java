/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.ConcurrencyAutoConfiguration;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestTemplate;

/**
 * End-to-end integration tests for concurrent task execution (DFX-002 NF-1~NF-3, S-17~S-22).
 *
 * <p>These tests exercise the full chain — HTTP → {@code A2aJsonRpcController} → real
 * {@link TaskAdmissionControl} → real {@code DefaultRequestHandler} → stub
 * {@link AgentHandler} — with <em>multiple requests in parallel</em>, verifying:
 * <ul>
 *   <li>NF-1: CAS admission count accuracy under high concurrency (no over-admission)
 *   <li>NF-2: Parallel tasks use independent handler invocations (no shared state)
 *   <li>NF-3: No context leakage between parallel tasks
 *   <li>S-17: Task-A failure does not affect Task-B
 *   <li>S-20: Parallel tasks execute concurrently (not serialized)
 * </ul>
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ConcurrencyParallelE2EIntegrationTest.ParallelTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openjiuwen.service.concurrency.max-concurrent-tasks=-1")
@AutoConfigureTestRestTemplate
class ConcurrencyParallelE2EIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskAdmissionGate admissionGate;

    @Autowired
    private ActiveTaskQuery activeTaskQuery;

    private RestTemplate concurrentRest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        return new RestTemplate(factory);
    }

    @BeforeEach
    void resetState() {
        if (admissionGate instanceof TaskAdmissionControl tac) {
            tac.reset();
        }
        ParallelAgent.reset();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void highConcurrency_admissionCountExact_noOverAdmission() throws Exception {
        int threadCount = 50;
        if (admissionGate instanceof TaskAdmissionControl tac) {
            tac.reset();
        }

        ExecutorService pool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        String baseUrl = "http://localhost:" + port + "/a2a/";

        try {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    RestTemplate rt = concurrentRest();
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", "concurrent-" + idx, "hello-" + idx);
                    ResponseEntity<String> resp = rt.postForEntity(baseUrl,
                            new HttpEntity<>(body, headers), String.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        results.add(1);
                    }
                    return null;
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(120, TimeUnit.SECONDS);
        }

        int admitted = results.size();
        assertThat(admitted).isEqualTo(threadCount);
        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void parallelTasks_executeConcurrently_notSerialized() throws Exception {
        int taskCount = 5;
        CountDownLatch allStarted = new CountDownLatch(taskCount);
        CountDownLatch releaseAll = new CountDownLatch(1);
        ParallelAgent.configure(allStarted, releaseAll);

        ExecutorService pool = new ThreadPoolExecutor(taskCount, taskCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                final String convId = "parallel-conv-" + i;
                final String text = "parallel-" + i;
                futures.add(CompletableFuture.runAsync(() -> {
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendStreamingMessage", convId, text);
                    rest.postForEntity(
                            "http://localhost:" + port + "/a2a/",
                            new HttpEntity<>(body, headers), String.class);
                }, pool));
            }

            boolean allUp = allStarted.await(10, TimeUnit.SECONDS);
            assertThat(allStarted.getCount())
                    .as("All %d tasks should start concurrently, but %d did not start",
                            taskCount, allStarted.getCount())
                    .isZero();

            releaseAll.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void parallelTasks_noContextLeak() throws Exception {
        int taskCount = 10;
        ExecutorService pool = new ThreadPoolExecutor(taskCount, taskCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                final String convId = "ctx-leak-" + i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", convId, "data-" + convId);
                    ResponseEntity<String> resp = rest.postForEntity(
                            "http://localhost:" + port + "/a2a/",
                            new HttpEntity<>(body, headers), String.class);
                    return resp.getBody();
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        for (int i = 0; i < taskCount; i++) {
            String body = futures.get(i).get();
            String expectedConv = "ctx-leak-" + i;
            assertThat(body)
                    .as("Task %d response should contain its own conversationId", i)
                    .contains(expectedConv);
            for (int j = 0; j < taskCount; j++) {
                if (j != i) {
                    assertThat(body)
                            .as("Task %d response should not contain conversationId of task %d", i, j)
                            .doesNotContain("ctx-leak-" + j);
                }
            }
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void taskAFailure_doesNotAffectTaskB() throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseBoth = new CountDownLatch(1);
        ParallelAgent.configure(bothStarted, releaseBoth);

        try {
            // Task-A uses SendMessage: the failure trigger ("fail-sync-query")
            // is only wired into ParallelAgent.query(), not streamQuery().
            CompletableFuture<String> taskA = CompletableFuture.supplyAsync(() -> {
                HttpHeaders headers = jsonHeaders();
                String body = jsonRpc("SendMessage", "fail-conv", "fail-sync-query");
                ResponseEntity<String> resp = rest.postForEntity("http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(body, headers), String.class);
                return resp.getBody();
            }, pool);

            CompletableFuture<String> taskB = CompletableFuture.supplyAsync(() -> {
                HttpHeaders headers = jsonHeaders();
                String body = jsonRpc("SendMessage", "ok-conv", "ok-data");
                ResponseEntity<String> resp = rest.postForEntity("http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(body, headers), String.class);
                return resp.getBody();
            }, pool);

            bothStarted.await(10, TimeUnit.SECONDS);
            releaseBoth.countDown();

            taskB.get(15, TimeUnit.SECONDS);
            taskA.get(15, TimeUnit.SECONDS);

            // Task-A must have failed (T-10: failure is contained, not silent)
            String failBody = taskA.get();
            assertThat(failBody).contains("TASK_STATE_FAILED");
            // Task-B must have completed normally, unaffected by Task-A's failure
            String okBody = taskB.get();
            assertThat(okBody).contains("ok-conv");
            assertThat(okBody).doesNotContain("TASK_STATE_FAILED");
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentAdmissionAndRelease_countStaysAccurate() throws Exception {
        int threadCount = 20;
        ExecutorService pool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        AtomicInteger successCount = new AtomicInteger(0);
        String baseUrl = "http://localhost:" + port + "/a2a/";

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    RestTemplate rt = concurrentRest();
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", "mix-conv-" + idx, "hello-" + idx);
                    ResponseEntity<String> resp = rt.postForEntity(baseUrl,
                            new HttpEntity<>(body, headers), String.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(admissionGate.currentCount())
                .as("After all tasks complete, count must be 0 (no leak)")
                .isZero();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ParallelTestApp {

        @Bean
        @Primary
        AgentHandler parallelAgentHandler(TaskAdmissionGate admissionGate, TaskQuotaTracker quotaTracker) {
            return new ParallelAgent(admissionGate, quotaTracker);
        }

        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }

    static final class ParallelAgent implements AgentHandler {
        private static volatile CountDownLatch allStarted;
        private static volatile CountDownLatch releaseGate;
        private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger(0);
        private static final ConcurrentHashMap<String, String> conversationByThread =
                new ConcurrentHashMap<>();

        private final TaskAdmissionGate admissionGate;
        private final TaskQuotaTracker quotaTracker;

        ParallelAgent(TaskAdmissionGate admissionGate, TaskQuotaTracker quotaTracker) {
            this.admissionGate = admissionGate;
            this.quotaTracker = quotaTracker;
        }

        static void configure(CountDownLatch started, CountDownLatch release) {
            allStarted = started;
            releaseGate = release;
            ACTIVE_COUNT.set(0);
            conversationByThread.clear();
        }

        static void reset() {
            allStarted = null;
            releaseGate = null;
            ACTIVE_COUNT.set(0);
            conversationByThread.clear();
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            String convId = request.getConversationId();
            conversationByThread.put(Thread.currentThread().getName(), convId);
            int concurrent = ACTIVE_COUNT.incrementAndGet();
            try {
                if (allStarted != null) {
                    allStarted.countDown();
                }
                if (releaseGate != null) {
                    releaseGate.await(15, TimeUnit.SECONDS);
                }
                if ("fail-sync-query".equals(request.lastUserQuery())) {
                    throw new IllegalStateException("intentional failure");
                }
                quotaTracker.onTaskWorking(convId, "task-" + convId);
                return new QueryResponse(
                        Map.of("role", "assistant", "content", "reply-" + convId, "conversation_id", convId,
                                "concurrent_peak", concurrent),
                        convId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve interrupt status
                throw new IllegalStateException(e);
            } finally {
                ACTIVE_COUNT.decrementAndGet();
                quotaTracker.onTaskReleased(convId);
                admissionGate.release();
            }
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            String convId = request.getConversationId();
            conversationByThread.put(Thread.currentThread().getName(), convId);
            int concurrent = ACTIVE_COUNT.incrementAndGet();
            try {
                observer.onNext(new QueryChunk("chunk",
                        Map.of("content", "tick-" + convId, "conversation_id", convId)));
                if (allStarted != null) {
                    allStarted.countDown();
                }
                if (releaseGate != null) {
                    releaseGate.await(15, TimeUnit.SECONDS);
                }
                observer.onComplete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve interrupt status
            } finally {
                ACTIVE_COUNT.decrementAndGet();
                quotaTracker.onTaskReleased(convId);
                admissionGate.release();
            }
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String jsonRpc(String method, String contextId, String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"" + method + "\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\","
                + "\"contextId\":\"" + contextId + "\",\"parts\":[{\"kind\":\"text\",\"text\":\""
                + text + "\"}]}}}";
    }
}
