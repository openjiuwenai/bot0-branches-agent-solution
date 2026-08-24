/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.ConcurrencyAutoConfiguration;
import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration tests verifying the full admission-control chain:
 * HTTP → {@code A2aJsonRpcController} → real {@link TaskAdmissionControl} (via
 * {@link ConcurrencyAutoConfiguration}) → real {@code DefaultRequestHandler} →
 * stub {@link AgentHandler}.
 *
 * <p>Unlike {@code ConcurrencyAdmissionIntegrationTest} (which injects a stub
 * {@code TaskAdmissionGate}), this test boots the real
 * {@link ConcurrencyAutoConfiguration} with real {@link TaskAdmissionControl},
 * {@link TaskQuotaTracker}, and {@link ActiveTaskController} — exercising the
 * complete DFX-002 bean wiring chain.
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ConcurrencyE2EIntegrationTest.E2ETestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openjiuwen.service.concurrency.max-concurrent-tasks=1")
@AutoConfigureTestRestTemplate
class ConcurrencyE2EIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyE2EIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskAdmissionGate admissionGate;

    @Autowired
    private ActiveTaskQuery activeTaskQuery;

    @BeforeEach
    void resetState() {
        if (admissionGate instanceof TaskAdmissionControl tac) {
            tac.reset();
        }
        SlowAgent.reset();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_admitted_realAutoConfigChain() {
        HttpHeaders headers = jsonHeaders();
        String body = jsonRpc("SendMessage", "e2e-admit", "hello");

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_rejected503_whenRealLimitReached() throws InterruptedException {
        HttpHeaders headers = jsonHeaders();

        ExecutorService first = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "e2e-slow"); t.setUncaughtExceptionHandler((tt, e) -> logError("rejected503", e)); return t; });
        first.submit(() -> {
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendStreamingMessage", "e2e-slow", "slow"), headers), String.class);
        });
        assertThat(SlowAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "e2e-reject", "reject"), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        SlowAgent.stopSlow(); // signal thread to stop
        first.shutdown();
        first.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void quotaReleased_afterTaskCompletes_newRequestAdmitted() {
        HttpHeaders headers = jsonHeaders();

        rest.postForEntity("http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "e2e-first", "hello"), headers), String.class);

        ResponseEntity<String> second = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "e2e-second", "world"), headers), String.class);

        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void activeTaskEndpoint_reflectsRealAdmissionState() throws InterruptedException {
        ExecutorService slow = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "e2e-active"); t.setUncaughtExceptionHandler((tt, e) -> logError("activeTaskEndpoint", e)); return t; });
        slow.submit(() -> {
            HttpHeaders headers = jsonHeaders();
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendStreamingMessage", "e2e-active", "slow"), headers), String.class);
        });
        assertThat(SlowAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<Map> snapshot = rest.getForEntity(
                "http://localhost:" + port + "/v1/current_active_tasks", Map.class);

        assertThat(snapshot.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = snapshot.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("maxConcurrentTasks", 1);
        assertThat(body).containsEntry("currentActiveTasks", 1);

        SlowAgent.stopSlow(); // signal thread to stop
        slow.shutdown();
        slow.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void getTask_notThrottled_whenAtLimit() throws InterruptedException {
        HttpHeaders headers = jsonHeaders();

        ExecutorService slow = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "e2e-slow2"); t.setUncaughtExceptionHandler((tt, e) -> logError("getTask-notThrottled", e)); return t; });
        slow.submit(() -> {
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendStreamingMessage", "e2e-slow2", "slow"), headers), String.class);
        });
        assertThat(SlowAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        String getTaskBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-gt\","
                + "\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"e2e-slow2\"}}";

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(getTaskBody, headers), String.class);

        assertThat(response.getStatusCode().value()).isNotEqualTo(503);

        SlowAgent.stopSlow(); // signal thread to stop
        slow.shutdown();
        slow.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void failingTask_releasesQuota_newRequestAdmitted() {
        HttpHeaders headers = jsonHeaders();

        rest.postForEntity("http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "fail-sync-query", "trigger failure"), headers), String.class);

        ResponseEntity<String> next = rest.postForEntity(
                "http://localhost:" + port + "/a2a/",
                new HttpEntity<>(jsonRpc("SendMessage", "e2e-after-fail", "hello"), headers), String.class);

        assertThat(next.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(admissionGate.currentCount()).isZero();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrencyLogs_emittedOnAdmitAndRelease() {
        org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(slf4jLogger).isInstanceOf(ch.qos.logback.classic.Logger.class);
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) slf4jLogger;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            HttpHeaders headers = jsonHeaders();
            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "e2e-log-test", "hello"), headers), String.class);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("[CONCURRENCY]"))
                    .toList();

            assertThat(messages)
                    .as("should contain task_admitted and task_released logs, total captured=%d",
                            appender.list.size())
                    .anyMatch(m -> m.contains("task_admitted"))
                    .anyMatch(m -> m.contains("task_released"));
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrencyLogs_emittedOnRejection() throws InterruptedException {
        org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(slf4jLogger).isInstanceOf(ch.qos.logback.classic.Logger.class);
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) slf4jLogger;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            HttpHeaders headers = jsonHeaders();

            ExecutorService slow = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "e2e-log-slow"); t.setUncaughtExceptionHandler((tt, e) -> logError("rejection-logs", e)); return t; });
            slow.submit(() -> {
                rest.postForEntity("http://localhost:" + port + "/a2a/",
                        new HttpEntity<>(jsonRpc("SendStreamingMessage", "e2e-log-slow", "slow"), headers), String.class);
            });
            assertThat(SlowAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

            rest.postForEntity("http://localhost:" + port + "/a2a/",
                    new HttpEntity<>(jsonRpc("SendMessage", "e2e-log-reject", "reject"), headers), String.class);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("[CONCURRENCY]"))
                    .toList();

            assertThat(messages)
                    .as("should contain task_rejected log, total captured=%d", appender.list.size())
                    .anyMatch(m -> m.contains("task_rejected"));

            SlowAgent.stopSlow(); // signal thread to stop
            slow.shutdown();
            slow.awaitTermination(2, TimeUnit.SECONDS);
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class E2ETestApp {
        @Bean
        @Primary
        AgentHandler slowAgentHandler(TaskQuotaTracker quotaTracker) {
            return new SlowAgent(quotaTracker);
        }

        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }

    static final class SlowAgent implements AgentHandler {
        private static volatile CountDownLatch startedLatch = new CountDownLatch(1);
        private static volatile boolean stopped;

        private final TaskQuotaTracker quotaTracker;

        SlowAgent(TaskQuotaTracker quotaTracker) {
            this.quotaTracker = quotaTracker;
        }

        static void reset() {
            startedLatch = new CountDownLatch(1);
            stopped = false;
        }

        static void stopSlow() {
            stopped = true;
        }

        static boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            try {
                if ("fail-sync-query".equals(request.lastUserQuery())) {
                    throw new IllegalStateException("intentional failure");
                }
                quotaTracker.onTaskWorking(request.getConversationId(), "task-" + request.getConversationId());
                return new QueryResponse(Map.of("role", "assistant", "content", "sync-reply"),
                        request.getConversationId());
            } finally {
                quotaTracker.onTaskReleased(request.getConversationId());
            }
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            try {
                quotaTracker.onTaskWorking(request.getConversationId(), "task-" + request.getConversationId());
                observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
                startedLatch.countDown();
                while (!observer.isCancelled() && !stopped) {
                    Thread.sleep(50);
                }
                observer.onComplete();
            } catch (InterruptedException e) {
                preserveInterrupt(); // preserve interrupt status
            } finally {
                quotaTracker.onTaskReleased(request.getConversationId());
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
