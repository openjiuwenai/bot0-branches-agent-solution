/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end integration tests using the real {@link JiuwenCoreAgentExtHandler}
 * chain: HTTP → {@code A2aJsonRpcController} → real {@code DefaultRequestHandler}
 * → real {@code A2AEnabledServeOrchestrator} → real
 * {@link JiuwenCoreAgentExtHandler} → real {@code Runner.runAgent} /
 * {@code Runner.runAgentStreaming} → per-Task agent via
 * {@link AgentInstanceManager}.
 *
 * <p>Unlike {@code ConcurrencyE2EIntegrationTest} (which uses a stub
 * {@code AgentHandler}), this test exercises the full DFX-002 per-Task agent
 * lifecycle inside the ext handler:
 * <ul>
 *   <li>{@code resolveTaskAgent} → {@code AgentInstanceManager.acquire} →
 *       {@code AgentFactory.create} per request
 *   <li>{@code currentTaskAgent} ThreadLocal set / remove
 *   <li>{@code executeAgent} / {@code executeAgentStreaming} overrides use the
 *       per-Task agent (not the constructor singleton)
 *   <li>{@code releaseTaskResources} in finally block releases admission, quota,
 *       and agent
 * </ul>
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = JiuwenCoreAgentExtHandlerE2EIntegrationTest.ExtHandlerE2ETestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openjiuwen.service.concurrency.max-concurrent-tasks=1")
@AutoConfigureTestRestTemplate
class JiuwenCoreAgentExtHandlerE2EIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentExtHandlerE2EIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskAdmissionGate admissionGate;

    @Autowired
    private TrackingAgentFactory agentFactory;

    @BeforeEach
    void resetState() {
        if (admissionGate instanceof TaskAdmissionControl tac) {
            tac.reset();
        }
        agentFactory.reset();
        TrackingAgent.resetLatches();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_usesPerTaskAgent_notConstructor() {
        HttpHeaders headers = jsonHeaders();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-conv-1", "hello"), headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).doesNotContain("constructor-placeholder");
        assertThat(agentFactory.getCreatedCount()).isEqualTo(1);
        String agentIdentity = agentFactory.getCreatedIdentities().get(0);
        assertThat(response.getBody()).contains(agentIdentity);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void twoSendMessage_getDifferentPerTaskAgents() {
        HttpHeaders headers = jsonHeaders();

        ResponseEntity<String> resp1 = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-conv-a", "hello"), headers), String.class);

        ResponseEntity<String> resp2 = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-conv-b", "world"), headers), String.class);

        assertThat(resp1.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp2.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(agentFactory.getCreatedCount()).isEqualTo(2);
        String identity1 = agentFactory.getCreatedIdentities().get(0);
        String identity2 = agentFactory.getCreatedIdentities().get(1);
        assertThat(identity1).isNotEqualTo(identity2);
        assertThat(resp1.getBody()).contains(identity1);
        assertThat(resp1.getBody()).doesNotContain(identity2);
        assertThat(resp2.getBody()).contains(identity2);
        assertThat(resp2.getBody()).doesNotContain(identity1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendStreamingMessage_usesPerTaskAgent_notConstructor() {
        HttpHeaders headers = jsonHeaders();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendStreamingMessage", "ext-stream-1", "hello"), headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).doesNotContain("constructor-placeholder");
        assertThat(agentFactory.getCreatedCount()).isEqualTo(1);
        String agentIdentity = agentFactory.getCreatedIdentities().get(0);
        assertThat(response.getBody()).contains(agentIdentity);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void quotaReleased_afterRequestCompletes() {
        HttpHeaders headers = jsonHeaders();

        rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-release", "hello"), headers), String.class);

        assertThat(admissionGate.currentCount()).isZero();
        assertThat(agentFactory.getDestroyedCount()).isEqualTo(1);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_throttled503_whenLimitReached() throws InterruptedException {
        HttpHeaders headers = jsonHeaders();

        ExecutorService first = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "ext-block"); t.setUncaughtExceptionHandler((tt, e) -> logError("throttled503", e)); return t; });
        first.submit(() -> {
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "ext-block", "block"), headers), String.class);
        });
        assertThat(TrackingAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-reject", "hello"), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);

        TrackingAgent.releaseBlock();
        first.shutdown();
        first.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void failingTask_releasesAllResources_newRequestAdmitted() {
        HttpHeaders headers = jsonHeaders();

        rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-fail", "trigger failure"), headers), String.class);

        assertThat(admissionGate.currentCount()).isZero();
        assertThat(agentFactory.getDestroyedCount()).isEqualTo(1);

        ResponseEntity<String> next = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-after-fail", "hello"), headers), String.class);

        assertThat(next.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void activeTaskEndpoint_reflectsRealAdmissionState() throws InterruptedException {
        HttpHeaders headers = jsonHeaders();

        ExecutorService slow = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "ext-active"); t.setUncaughtExceptionHandler((tt, e) -> logError("activeTaskEndpoint", e)); return t; });
        slow.submit(() -> {
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "ext-active", "block"), headers), String.class);
        });
        assertThat(TrackingAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<Map> snapshot = rest.getForEntity(
                "http://localhost:" + port + "/v1/current_active_tasks", Map.class);

        assertThat(snapshot.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = snapshot.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("maxConcurrentTasks", 1);
        assertThat(body).containsEntry("currentActiveTasks", 1);

        TrackingAgent.releaseBlock();
        slow.shutdown();
        slow.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void agentCreationFailure_doesNotLeakAdmission() {
        HttpHeaders headers = jsonHeaders();
        agentFactory.failNextCreate();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-create-fail", "hello"), headers), String.class);

        // G+ design: creation failure → SDK error path → FAILED Task (HTTP 200), not HTTP 500.
        // The critical invariant is: admission count returns to zero.
        assertThat(admissionGate.currentCount())
                .as("admission count must be zero after agent creation failure")
                .isZero();

        ResponseEntity<String> next = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-after-create-fail", "hello"), headers), String.class);

        assertThat(next.getStatusCode().is2xxSuccessful())
                .as("subsequent request must be admitted after creation failure")
                .isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void inputRequired_releasesAdmission_newRequestAdmitted() {
        HttpHeaders headers = jsonHeaders();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendStreamingMessage", "ext-input-req", "require input"), headers),
                String.class);

        // Task enters INPUT_REQUIRED → handler finally releases admission
        assertThat(admissionGate.currentCount())
                .as("admission count must be zero after INPUT_REQUIRED")
                .isZero();
        assertThat(agentFactory.getDestroyedCount())
                .as("agent must be destroyed after INPUT_REQUIRED")
                .isEqualTo(1);

        ResponseEntity<String> next = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-after-input-req", "hello"), headers), String.class);

        assertThat(next.getStatusCode().is2xxSuccessful())
                .as("subsequent request must be admitted after INPUT_REQUIRED release")
                .isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void inputRequired_resumeRejected_whenQuotaFull() throws InterruptedException {
        HttpHeaders headers = jsonHeaders();

        // Step 1: trigger INPUT_REQUIRED → task enters INPUT_REQUIRED, admission released
        rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendStreamingMessage", "ext-ir-conv", "require input"), headers),
                String.class);

        assertThat(admissionGate.currentCount())
                .as("admission must be zero after INPUT_REQUIRED")
                .isZero();

        // Step 2: start a blocking task with different contextId → fills quota (limit=1)
        ExecutorService blocker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "ext-blocker"); t.setUncaughtExceptionHandler((tt, e) -> logError("resumeRejected", e)); return t; });
        blocker.submit(() -> {
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "ext-blocker", "block"), headers), String.class);
        });
        assertThat(TrackingAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        // Step 3: resume the INPUT_REQUIRED task → admission full → rejected
        ResponseEntity<String> resume = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "ext-ir-conv", "continue please"), headers), String.class);

        assertThat(resume.getStatusCode().value())
                .as("INPUT_REQUIRED resume must be rejected when quota is full")
                .isEqualTo(503);

        TrackingAgent.releaseBlock();
        blocker.shutdown();
        blocker.awaitTermination(5, TimeUnit.SECONDS);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ExtHandlerE2ETestApp {
        @Bean
        TrackingAgentFactory trackingAgentFactory() {
            return new TrackingAgentFactory();
        }

        @Bean
        @Primary
        AgentHandler extHandler(AgentInstanceManager agentManager) {
            return new JiuwenCoreAgentExtHandler(new TrackingAgent("constructor-placeholder"));
        }

        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }

    static final class TrackingAgentFactory implements AgentFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final List<String> createdIdentities = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> destroyedAgents = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean failNext = false;

        void failNextCreate() {
            failNext = true;
        }

        @Override
        public Object create() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("Simulated agent creation failure");
            }
            String identity = "agent-" + counter.incrementAndGet();
            TrackingAgent agent = new TrackingAgent(identity);
            createdIdentities.add(identity);
            return agent;
        }

        @Override
        public void destroy(Object agent) {
            destroyedAgents.add(agent);
        }

        int getCreatedCount() {
            return createdIdentities.size();
        }
        List<String> getCreatedIdentities() {
            return new ArrayList<>(createdIdentities);
        }
        int getDestroyedCount() {
            return destroyedAgents.size();
        }

        void reset() {
            counter.set(0);
            createdIdentities.clear();
            destroyedAgents.clear();
            failNext = false;
        }
    }

    /**
     * Test agent stub that blocks on a latch to simulate long-running work.
     * Supports both synchronous and streaming invocation patterns.
     */
    public static final class TrackingAgent {
        private static volatile CountDownLatch startedLatch = new CountDownLatch(1);
        private static volatile CountDownLatch blockLatch = new CountDownLatch(1);

        private final String identity;

        TrackingAgent(String identity) {
            this.identity = identity;
        }

        static void resetLatches() {
            startedLatch = new CountDownLatch(1);
            blockLatch = new CountDownLatch(1);
        }

        static boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        static void releaseBlock() {
            blockLatch.countDown();
        }

        /**
         * Handles a synchronous invocation, blocking if the query is "block".
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @return a map containing the agent identity and result type
         */
        public Object invoke(Object inputs, Session session) {
            if (inputs instanceof Map<?, ?> map) {
                Object query = map.get("query");
                if ("block".equals(query)) {
                    startedLatch.countDown();
                    awaitBlockLatch();
                }
                if ("trigger failure".equals(query)) {
                    throw new IllegalStateException("intentional failure");
                }
            }
            return Map.of("output", identity, "result_type", "answer");
        }

        /**
         * Handles a streaming invocation, returning a single-element stream.
         *
         * @param inputs the invocation inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return an iterator over the stream output
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            if (inputs instanceof Map<?, ?> map && "require input".equals(map.get("query"))) {
                return List.<Object>of(Map.of("type", "__interaction__", "message", "Need more info")).iterator();
            }
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
        }

        private static void awaitBlockLatch() {
            try {
                blockLatch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                preserveInterrupt(); // preserve interrupt status
            }
        }
    }

    private static void logError(String context, Throwable e) {
        log.error("test error in {}: {}", context, e.getMessage(), e);
    }

    @SuppressWarnings("java:S2142")
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
