/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.test.annotation.DirtiesContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end parallel admission tests under a LIMITED quota
 * ({@code max-concurrent-tasks=2}).
 *
 * <p>Complements the {@code *ParallelE2EIntegrationTest} classes which run with
 * {@code max=-1}: under an unlimited quota {@code currentCount()} is always 0,
 * so quota-leak assertions there are trivially true. This class makes the quota
 * real: concurrent requests must be admitted exactly up to the limit, extra
 * requests must be rejected with HTTP 503, and after release the counter must
 * drain to zero — verifying the admission CAS path and leak-freedom for real.
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ConcurrencyLimitedParallelE2EIntegrationTest.LimitedTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openjiuwen.service.concurrency.max-concurrent-tasks=2")
@AutoConfigureTestRestTemplate
class ConcurrencyLimitedParallelE2EIntegrationTest {

    private static final int LIMIT = 2;

    /** Extracts the task id from an SSE data line of an A2A task event. */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("\"taskId\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskAdmissionGate admissionGate;

    @BeforeEach
    void resetState() {
        if (admissionGate instanceof TaskAdmissionControl tac) {
            tac.reset();
        }
        GatedAgent.reset();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentRequests_exactlyLimitAdmitted_othersRejected503() throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(
                LIMIT + 3, LIMIT + 3, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        List<CompletableFuture<ResponseEntity<String>>> admitted = new ArrayList<>();
        try {
            // 1. Fire LIMIT requests that block inside the agent — they hold the quota
            for (int i = 0; i < LIMIT; i++) {
                final String convId = "limited-admit-" + i;
                final String text = "hold-" + i;
                admitted.add(CompletableFuture.supplyAsync(() -> rest.postForEntity(
                        "http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(jsonRpc("SendStreamingMessage", convId, text), jsonHeaders()),
                        String.class), pool));
            }
            // Deterministic admission: both executors incremented the counter
            // before the agent blocks, so later requests hit a full quota.
            assertThat(GatedAgent.awaitStarted(10, TimeUnit.SECONDS)).isTrue();
            assertThat(admissionGate.currentCount())
                    .as("Exactly %d tasks must hold the quota while blocked", LIMIT)
                    .isEqualTo(LIMIT);

            // 2. Extra requests must be rejected with HTTP 503 (not queued, not admitted)
            List<CompletableFuture<Integer>> rejected = fireRejectedRequests(pool);
            for (CompletableFuture<Integer> status : rejected) {
                assertThat(status.get(15, TimeUnit.SECONDS))
                        .as("Request beyond the limit must be rejected with HTTP 503")
                        .isEqualTo(503);
            }
            assertThat(admissionGate.currentCount())
                    .as("Rejected requests must not occupy quota")
                    .isEqualTo(LIMIT);

            // 3. Release the blocked tasks; all complete and the quota drains to zero
            GatedAgent.release();
            for (CompletableFuture<ResponseEntity<String>> future : admitted) {
                assertThat(future.get(15, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
            }
            assertThat(admissionGate.currentCount())
                    .as("After all admitted tasks complete the counter must drain to zero (no leak)")
                    .isZero();
        } finally {
            GatedAgent.release();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void quotaDrainedAfterParallelCompletion_newRequestAdmitted() throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(
                LIMIT, LIMIT, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            List<CompletableFuture<ResponseEntity<String>>> held = new ArrayList<>();
            for (int i = 0; i < LIMIT; i++) {
                final String convId = "drain-" + i;
                final String text = "hold-" + i;
                held.add(CompletableFuture.supplyAsync(() -> rest.postForEntity(
                        "http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(jsonRpc("SendStreamingMessage", convId, text), jsonHeaders()),
                        String.class), pool));
            }
            assertThat(GatedAgent.awaitStarted(10, TimeUnit.SECONDS)).isTrue();
            GatedAgent.release();
            for (CompletableFuture<ResponseEntity<String>> future : held) {
                assertThat(future.get(15, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
            }
            assertThat(admissionGate.currentCount())
                    .as("Counter must drain to zero after parallel completion")
                    .isZero();

            // The drained quota must admit a new request immediately
            ResponseEntity<String> next = rest.postForEntity(
                    "http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "drain-after", "hello"), jsonHeaders()), String.class);
            assertThat(next.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(next.getBody()).contains("drain-after");
        } finally {
            GatedAgent.release();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @Disabled("Known product defect (S-21): A2aJsonRpcController.handleJsonRpc does not route "
            + "CancelTask — the request fails with JSON-RPC -32601 and A2AAgentExecutor.cancel() "
            + "is unreachable through the JSON-RPC surface. Re-enable once the product fix lands.")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void canceledTask_releasesQuota_endsCanceled_newRequestAdmitted() throws Exception {
        // S-21: cancel an in-flight streaming task through the A2A protocol —
        // the task must end CANCELED, the quota must be released, and the
        // runtime must keep serving new requests.
        String convId = "limited-cancel";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // 1. Open the SSE stream and read incrementally until the task id appears
        HttpResponse<InputStream> sse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/a2a/"))
                        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                jsonRpc("SendStreamingMessage", convId, "hold-cancel")))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(sse.statusCode()).isEqualTo(200);

        String taskId = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sse.body(), StandardCharsets.UTF_8))) {
            // Non-blocking read loop: reader.readLine() would hang forever if
            // the server stops sending events, so poll reader.ready() instead.
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && taskId == null) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    Matcher matcher = TASK_ID_PATTERN.matcher(line);
                    if (matcher.find()) {
                        taskId = matcher.group(1);
                    }
                } else {
                    Thread.sleep(50);
                }
            }
            assertThat(taskId).as("task id must appear in the early SSE events").isNotNull();

            // 2. Cancel the task through the A2A protocol (CancelTask)
            String cancelBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-cancel\",\"method\":\"CancelTask\","
                    + "\"params\":{\"id\":\"" + taskId + "\"}}";
            HttpResponse<String> cancelResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/a2a/"))
                            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(cancelBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cancelResp.statusCode()).as("CancelTask must return 2xx").isEqualTo(200);

            // 3. Drain the SSE stream (non-blocking): the task must reach
            // CANCELED and the stream must end
            boolean canceledSeen = false;
            boolean streamEnded = false;
            long drainDeadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < drainDeadline && !streamEnded) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        streamEnded = true;
                    } else if (line.contains("TASK_STATE_CANCELED")) {
                        canceledSeen = true;
                    } else {
                        /* other state line — continue */
                    }
                } else if (canceledSeen) {
                    // give the server a moment to close, then re-check
                    Thread.sleep(100);
                } else {
                    /* non-state line, continue */
                    Thread.sleep(50);
                }
            }
            assertThat(canceledSeen)
                    .as("canceled task must emit a TASK_STATE_CANCELED status event")
                    .isTrue();
        } finally {
            GatedAgent.release();
        }

        // 4. Quota released and a new request is admitted
        awaitCountZero();
        ResponseEntity<String> next = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "after-cancel", "hello"), jsonHeaders()), String.class);
        assertThat(next.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private List<CompletableFuture<Integer>> fireRejectedRequests(ExecutorService pool) {
        List<CompletableFuture<Integer>> rejected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String convId = "limited-reject-" + i;
            final String text = "extra-" + i;
            rejected.add(CompletableFuture.supplyAsync(() -> rest.postForEntity(
                    "http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendStreamingMessage", convId, text), jsonHeaders()),
                    String.class).getStatusCode().value(), pool));
        }
        return rejected;
    }

    private void awaitCountZero() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && admissionGate.currentCount() != 0) {
            Thread.sleep(100);
        }
        assertThat(admissionGate.currentCount())
                .as("admission count must drain to zero")
                .isZero();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class LimitedTestApp {

        @Bean
        @Primary
        AgentHandler gatedAgentHandler(TaskQuotaTracker quotaTracker) {
            return new GatedAgent(quotaTracker);
        }

        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }

    /**
     * Blocking agent stub: streaming requests park on a release latch so tests
     * can deterministically hold the admission quota open. Does NOT touch the
     * admission gate — permit release belongs to {@code A2AAgentExecutor}.
     */
    static final class GatedAgent implements AgentHandler {
        private static volatile CountDownLatch startedLatch = new CountDownLatch(LIMIT);
        private static volatile CountDownLatch releaseLatch = new CountDownLatch(1);
        private static final AtomicInteger ENTERED = new AtomicInteger();

        private final TaskQuotaTracker quotaTracker;

        GatedAgent(TaskQuotaTracker quotaTracker) {
            this.quotaTracker = quotaTracker;
        }

        static void reset() {
            startedLatch = new CountDownLatch(LIMIT);
            releaseLatch = new CountDownLatch(1);
            ENTERED.set(0);
        }

        static boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        static void release() {
            releaseLatch.countDown();
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            try {
                quotaTracker.onTaskWorking(request.getConversationId(), "task-" + request.getConversationId());
                return new QueryResponse(Map.of("role", "assistant", "content", "sync-" + request.getConversationId()),
                        request.getConversationId());
            } finally {
                quotaTracker.onTaskReleased(request.getConversationId());
            }
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            try {
                quotaTracker.onTaskWorking(request.getConversationId(), "task-" + request.getConversationId());
                observer.onNext(new QueryChunk("chunk", Map.of("content", "tick-" + request.getConversationId())));
                if (ENTERED.incrementAndGet() <= LIMIT) {
                    startedLatch.countDown();
                }
                // Park until released OR cancelled — cancellation (S-21) must
                // be able to unblock an in-flight streaming task
                while (!releaseLatch.await(100, TimeUnit.MILLISECONDS) && !observer.isCancelled()) {
                    // no-op: release gate only
                }
                observer.onComplete();
            } catch (InterruptedException e) {
                preserveInterrupt(); // preserve interrupt status
            } finally {
                quotaTracker.onTaskReleased(request.getConversationId());
            }
        }
    }

    private static void preserveInterrupt() {
        Thread.currentThread().interrupt();
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
