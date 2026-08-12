/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.ConcurrencyAutoConfiguration;
import com.openjiuwen.service.app.controller.runtime.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.spi.AgentHandler;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end integration tests for concurrent per-Task agent execution using
 * the real {@link JiuwenCoreAgentExtHandler} chain (DFX-002 NF-1~NF-3, S-17~S-22).
 *
 * <p>These tests exercise the full chain — HTTP → {@code A2aJsonRpcController}
 * → real {@code DefaultRequestHandler} → real {@code A2AEnabledServeOrchestrator}
 * → real {@link JiuwenCoreAgentExtHandler} → real {@code Runner.runAgent} →
 * per-Task agent via {@link AgentInstanceManager} — with <em>multiple requests
 * in parallel</em>, verifying:
 * <ul>
 *   <li>Each parallel task gets a different per-Task agent instance
 *   <li>Per-Task agent identity appears in the response (not the constructor
 *       placeholder)
 *   <li>No context leakage between parallel tasks
 *   <li>Task-A failure does not affect Task-B
 *   <li>All agents are destroyed after completion (no leak)
 * </ul>
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = JiuwenCoreAgentExtHandlerParallelE2EIntegrationTest.ParallelExtTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openjiuwen.service.concurrency.max-concurrent-tasks=-1")
@AutoConfigureTestRestTemplate
class JiuwenCoreAgentExtHandlerParallelE2EIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskAdmissionGate admissionGate;

    @Autowired
    private ParallelAgentFactory agentFactory;

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
        agentFactory.reset();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void parallelTasks_eachGetsDifferentPerTaskAgent() throws Exception {
        int taskCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(taskCount);
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                final String convId = "parallel-ext-" + i;
                final String text = "hello-" + i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", convId, text);
                    ResponseEntity<String> resp = concurrentRest().postForEntity(
                            "http://localhost:" + port + "/a2a/",
                            new HttpEntity<>(body, headers), String.class);
                    return resp.getBody();
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(agentFactory.getCreatedCount()).isEqualTo(taskCount);
        assertThat(agentFactory.getDestroyedCount()).isEqualTo(taskCount);

        List<String> identities = agentFactory.getCreatedIdentities();
        assertThat(identities).hasSize(taskCount);
        assertThat(new java.util.HashSet<>(identities)).hasSize(taskCount);

        java.util.Set<String> matchedIdentities = new java.util.HashSet<>();
        for (int i = 0; i < taskCount; i++) {
            String body = futures.get(i).get();
            assertThat(body).isNotNull();
            assertThat(body).doesNotContain("constructor-placeholder");
            for (String identity : identities) {
                if (body.contains(identity)) {
                    matchedIdentities.add(identity);
                }
            }
        }
        assertThat(matchedIdentities).hasSize(taskCount);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void parallelTasks_noContextLeak() throws Exception {
        int taskCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(taskCount);
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                final String convId = "ctx-leak-ext-" + i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", convId, "data-" + convId);
                    ResponseEntity<String> resp = concurrentRest().postForEntity(
                            "http://localhost:" + port + "/a2a/",
                            new HttpEntity<>(body, headers), String.class);
                    return resp.getBody();
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        List<String> identities = agentFactory.getCreatedIdentities();
        assertThat(identities).hasSize(taskCount);

        java.util.Map<String, String> identityByBody = new java.util.HashMap<>();
        for (int i = 0; i < taskCount; i++) {
            String body = futures.get(i).get();
            assertThat(body).isNotNull();
            assertThat(body).doesNotContain("constructor-placeholder");
            String matchedIdentity = null;
            for (String identity : identities) {
                if (body.contains(identity)) {
                    assertThat(matchedIdentity)
                            .as("Response for task %d should contain exactly one agent identity", i)
                            .isNull();
                    matchedIdentity = identity;
                }
            }
            assertThat(matchedIdentity)
                    .as("Response for task %d should contain an agent identity", i)
                    .isNotNull();
            assertThat(identityByBody.put(matchedIdentity, body))
                    .as("Agent identity %s should appear in only one response", matchedIdentity)
                    .isNull();
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void taskAFailure_doesNotAffectTaskB() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<CompletableFuture<String>> futures = new ArrayList<>();

        try {
            futures.add(CompletableFuture.supplyAsync(() -> {
                HttpHeaders headers = jsonHeaders();
                String body = jsonRpc("SendMessage", "fail-conv-ext", "trigger failure");
                ResponseEntity<String> resp = concurrentRest().postForEntity(
                        "http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(body, headers), String.class);
                return resp.getBody();
            }, pool));

            futures.add(CompletableFuture.supplyAsync(() -> {
                HttpHeaders headers = jsonHeaders();
                String body = jsonRpc("SendMessage", "ok-conv-ext", "ok-data");
                ResponseEntity<String> resp = concurrentRest().postForEntity(
                        "http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(body, headers), String.class);
                return resp.getBody();
            }, pool));

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(agentFactory.getCreatedCount()).isEqualTo(2);
        assertThat(agentFactory.getDestroyedCount()).isEqualTo(2);
        assertThat(admissionGate.currentCount()).isZero();

        String okBody = futures.get(1).get();
        assertThat(okBody).isNotNull();
        assertThat(okBody).doesNotContain("constructor-placeholder");
        List<String> identities = agentFactory.getCreatedIdentities();
        boolean containsAnyIdentity = false;
        for (String identity : identities) {
            if (okBody.contains(identity)) {
                containsAnyIdentity = true;
            }
        }
        assertThat(containsAnyIdentity)
                .as("OK task response should contain a per-Task agent identity")
                .isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void highConcurrency_allTasksComplete_allAgentsDestroyed() throws Exception {
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        String baseUrl = "http://localhost:" + port + "/a2a/";

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    RestTemplate rt = concurrentRest();
                    HttpHeaders headers = jsonHeaders();
                    String body = jsonRpc("SendMessage", "high-conc-ext-" + idx, "hello-" + idx);
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
        assertThat(agentFactory.getCreatedCount()).isEqualTo(threadCount);
        assertThat(agentFactory.getDestroyedCount()).isEqualTo(threadCount);
        assertThat(admissionGate.currentCount()).isZero();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ParallelExtTestApp {

        @Bean
        ParallelAgentFactory parallelAgentFactory() {
            return new ParallelAgentFactory();
        }

        @Bean
        @Primary
        AgentHandler extHandler(AgentInstanceManager agentManager) {
            return new JiuwenCoreAgentExtHandler(new ParallelAgent("constructor-placeholder"));
        }

        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }

    static final class ParallelAgentFactory implements AgentFactory {
        private final List<String> createdIdentities = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> destroyedAgents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Object create() {
            String identity = "pagent-" + java.util.UUID.randomUUID();
            ParallelAgent agent = new ParallelAgent(identity);
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
            createdIdentities.clear();
            destroyedAgents.clear();
        }
    }

    public static final class ParallelAgent {
        private final String identity;

        ParallelAgent(String identity) {
            this.identity = identity;
        }

        public Object invoke(Object inputs, Session session) {
            if (inputs instanceof Map<?, ?> map && "trigger failure".equals(map.get("query"))) {
                throw new IllegalStateException("intentional failure");
            }
            return Map.of("output", identity, "result_type", "answer");
        }

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", identity))).iterator();
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
