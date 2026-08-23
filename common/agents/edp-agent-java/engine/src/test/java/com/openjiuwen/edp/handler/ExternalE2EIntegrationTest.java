/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * External end-to-end tests that run against a real edp-agent instance
 * (default: http://localhost:8190).
 *
 * <p>These tests verify the full chain: Client → edp-agent → versatile-agent → Versatile backend.
 * They require all services to be running externally before execution.
 *
 * <p>Run with: {@code mvn test -Dexternal.e2e.enabled=true -pl engine}
 *
 * @since 0.1.2
 */
@EnabledIfSystemProperty(named = "external.e2e.enabled", matches = "true")
class ExternalE2EIntegrationTest {

    private static final String BASE_URL = System.getProperty("external.e2e.url", "http://localhost:8190");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Verify the agent card endpoint is accessible.
     *
     * @throws Exception if the test fails
     */
    @Test
    void agentCard_isAccessible() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/.well-known/agent-card.json"))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode card = MAPPER.readTree(resp.body());
        assertThat(card.get("name").asText()).isNotBlank();
    }

    /**
     * Verify SendMessage with a balance query completes successfully.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendMessage_balanceQuery_completesSuccessfully() throws Exception {
        String contextId = "ext-e2e-balance-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode result = sendA2AMessage(contextId, "帮我查询账户余额");
        assertThat(extractState(result)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
    }

    // ── SendStreamingMessage (JSON-RPC) streaming variants ──────────────

    /**
     * Verify SendStreamingMessage with a balance query reaches a terminal state.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendStreamingMessage_balanceQuery_terminalState() throws Exception {
        String contextId = "ext-stream-balance-" + UUID.randomUUID().toString().substring(0, 8);
        StreamResult sr = sendA2AStreamRaw(contextId, "帮我查询账户余额");
        assertThat(sr.terminalState)
                .as("streaming balance query must reach a terminal state")
                .isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        assertThat(sr.echoedContextId).isEqualTo(contextId);
    }

    /**
     * Verify SendStreamingMessage with a full three-round wealth purchase flow.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendStreamingMessage_wealthPurchase_fullThreeRoundFlow() throws Exception {
        String contextId = "ext-stream-wealth-" + UUID.randomUUID().toString().substring(0, 8);
        // Round 1: recommend products
        StreamResult r1 = sendA2AStreamRaw(contextId, "帮我推荐一些理财产品");
        assertThat(r1.terminalState).isEqualTo("TASK_STATE_COMPLETED");
        // Round 2: select product (may trigger INPUT_REQUIRED from ask_user)
        StreamResult r2 = sendA2AStreamRaw(contextId, "购买1号产品，金额5000元");
        assertThat(r2.terminalState)
                .as("streaming R2 must reach terminal state")
                .isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        // Round 3: confirm purchase (only if R2 was INPUT_REQUIRED)
        if ("TASK_STATE_INPUT_REQUIRED".equals(r2.terminalState)) {
            StreamResult r3 = sendA2AStreamRaw(contextId,
                    "确认购买1号339现管DXXJ-1339，5000元，请继续执行");
            assertThat(r3.terminalState).isEqualTo("TASK_STATE_COMPLETED");
        }
    }

    /**
     * Verify SendStreamingMessage with new conversations does not leak admission.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendStreamingMessage_newConversation_doesNotLeakAdmission() throws Exception {
        String ctx1 = "ext-stream-admit-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctx2 = "ext-stream-admit-b-" + UUID.randomUUID().toString().substring(0, 8);
        StreamResult s1 = sendA2AStreamRaw(ctx1, "帮我查询账户余额");
        assertThat(s1.terminalState).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        StreamResult s2 = sendA2AStreamRaw(ctx2, "帮我查询账户余额");
        assertThat(s2.terminalState).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
    }

    /**
     * Verify SendStreamingMessage with parallel tasks has no context leak.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendStreamingMessage_parallelTasks_noContextLeak() throws Exception {
        String ctxA = "ext-stream-par-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxB = "ext-stream-par-b-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            CompletableFuture<StreamResult> futureA = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AStreamRaw(ctxA, "帮我查询账户余额");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool);
            CompletableFuture<StreamResult> futureB = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AStreamRaw(ctxB, "帮我推荐一些理财产品");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool);
            StreamResult resultA = futureA.get(180, TimeUnit.SECONDS);
            StreamResult resultB = futureB.get(180, TimeUnit.SECONDS);
            assertThat(resultA.terminalState).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED", "");
            assertThat(resultB.terminalState).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED", "");
            if (!resultA.echoedContextId.isEmpty()) {
                assertThat(resultA.echoedContextId).isEqualTo(ctxA);
            }
            if (!resultB.echoedContextId.isEmpty()) {
                assertThat(resultB.echoedContextId).isEqualTo(ctxB);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Verify SendStreamingMessage with concurrent agent creation all succeed.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendStreamingMessage_concurrentAgentCreation_allSucceed() throws Exception {
        int count = 5;
        ExecutorService pool = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<CompletableFuture<StreamResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String ctx = "ext-stream-conc-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendA2AStreamRaw(ctx, "帮我查询账户余额");
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, pool));
            }
            int successCount = 0;
            for (CompletableFuture<StreamResult> f : futures) {
                StreamResult sr = f.get(240, TimeUnit.SECONDS);
                if ("TASK_STATE_COMPLETED".equals(sr.terminalState)
                        || "TASK_STATE_INPUT_REQUIRED".equals(sr.terminalState)) {
                    successCount++;
                }
            }
            Assumptions.assumeTrue(!admissionLimitConfigured() || successCount == count,
                    "all-succeed assertion applies to the unlimited-quota configuration");
            assertThat(successCount)
                    .as("all %d concurrent streaming requests should reach terminal state", count)
                    .isEqualTo(count);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Verify SendMessage with a full three-round wealth purchase flow.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendMessage_wealthPurchase_fullThreeRoundFlow() throws Exception {
        String contextId = "ext-e2e-wealth-" + UUID.randomUUID().toString().substring(0, 8);
        // Round 1: recommend products
        JsonNode r1 = sendA2AMessage(contextId, "帮我推荐一些理财产品");
        assertThat(extractState(r1)).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(extractText(r1)).contains("339现管DXXJ-1339");
        // Round 2: select product (may trigger INPUT_REQUIRED from ask_user)
        JsonNode r2 = sendA2AMessage(contextId, "购买1号产品，金额5000元");
        String r2State = extractState(r2);
        assertThat(r2State).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        // Round 3: confirm purchase (only if R2 was INPUT_REQUIRED)
        if ("TASK_STATE_INPUT_REQUIRED".equals(r2State)) {
            JsonNode r3 = sendA2AMessage(contextId,
                    "确认购买1号339现管DXXJ-1339，5000元，请继续执行");
            assertThat(extractState(r3)).isEqualTo("TASK_STATE_COMPLETED");
            assertThat(extractText(r3)).contains("购买成功");
        }
    }

    /**
     * Verify SendMessage with new conversations does not leak admission.
     *
     * @throws Exception if the test fails
     */
    @Test
    void sendMessage_newConversation_doesNotLeakAdmission() throws Exception {
        String ctx1 = "ext-e2e-admit-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctx2 = "ext-e2e-admit-b-" + UUID.randomUUID().toString().substring(0, 8);
        // First request
        JsonNode r1 = sendA2AMessage(ctx1, "帮我查询账户余额");
        assertThat(extractState(r1)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        // Second request on different conversation — should not be blocked
        JsonNode r2 = sendA2AMessage(ctx2, "帮我查询账户余额");
        assertThat(extractState(r2)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
    }

    /**
     * §7.2 并行任务执行隔离 — 上下文不泄露.
     * Send parallel requests with different contextIds, verify responses don't mix.
     *
     * @throws Exception if the test fails
     */
    @Test
    void parallelTasks_noContextLeak() throws Exception {
        String ctxA = "ext-parallel-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxB = "ext-parallel-b-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            CompletableFuture<JsonNode> futureA = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AMessage(ctxA, "帮我查询账户余额");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool);
            CompletableFuture<JsonNode> futureB = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AMessage(ctxB, "帮我推荐一些理财产品");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool);
            JsonNode resultA = futureA.get(120, TimeUnit.SECONDS);
            JsonNode resultB = futureB.get(120, TimeUnit.SECONDS);
            // Both should complete without errors (or one may be 503 if max-concurrent-tasks=1)
            String stateA = extractState(resultA);
            String stateB = extractState(resultB);
            assertThat(stateA).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED", "");
            assertThat(stateB).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED", "");
            // Verify contextIds are isolated — response A should not contain B's contextId and vice versa
            // (only check when task was actually created, not 503 rejected)
            String contextIdA = resultA.at("/result/task/contextId").asText("");
            String contextIdB = resultB.at("/result/task/contextId").asText("");
            if (!contextIdA.isEmpty()) {
                assertThat(contextIdA).isEqualTo(ctxA);
            }
            if (!contextIdB.isEmpty()) {
                assertThat(contextIdB).isEqualTo(ctxB);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 并发 Agent 创建线程安全.
     * Send multiple concurrent requests, all should succeed without errors.
     *
     * @throws Exception if the test fails
     */
    @Test
    void concurrentAgentCreation_allSucceed() throws Exception {
        int count = 5;
        ExecutorService pool = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String ctx = "ext-concurrent-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendA2AMessage(ctx, "帮我查询账户余额");
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, pool));
            }
            int successCount = 0;
            int rejectedCount = 0;
            for (CompletableFuture<JsonNode> f : futures) {
                JsonNode result = f.get(180, TimeUnit.SECONDS);
                String state = extractState(result);
                if ("TASK_STATE_COMPLETED".equals(state) || "TASK_STATE_INPUT_REQUIRED".equals(state)) {
                    successCount++;
                } else {
                    rejectedCount++; // 503 rejection when max-concurrent-tasks is limited
                }
            }
            assertThat(successCount + rejectedCount)
                    .as("all %d concurrent requests should produce a response", count)
                    .isEqualTo(count);
            // Unrestricted agent-creation safety: with the default unlimited
            // quota every request must succeed (L2 §7.2 并发创建旅程).
            Assumptions.assumeTrue(!admissionLimitConfigured() || rejectedCount == 0,
                    "all-succeed assertion applies to the unlimited-quota configuration");
            assertThat(successCount)
                    .as("all %d concurrent requests should succeed", count)
                    .isEqualTo(count);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 续传后任务恢复执行并进入终态释放额度.
     * Full multi-round flow: recommend → select → confirm purchase, all reach COMPLETED.
     *
     * @throws Exception if the test fails
     */
    @Test
    void inputRequired_resumeReachesTerminalState() throws Exception {
        String contextId = "ext-e2e-resume-" + UUID.randomUUID().toString().substring(0, 8);
        // Round 1: recommend
        JsonNode r1 = sendA2AMessage(contextId, "帮我推荐一些理财产品");
        assertThat(extractState(r1)).isEqualTo("TASK_STATE_COMPLETED");
        // Round 2: select
        JsonNode r2 = sendA2AMessage(contextId, "购买1号产品，金额5000元");
        String r2State = extractState(r2);
        if ("TASK_STATE_INPUT_REQUIRED".equals(r2State)) {
            // Round 3: confirm — must reach terminal state
            JsonNode r3 = sendA2AMessage(contextId,
                    "确认购买1号339现管DXXJ-1339，5000元，请继续执行");
            assertThat(extractState(r3))
                    .as("resumed task must reach terminal state")
                    .isEqualTo("TASK_STATE_COMPLETED");
            // Verify admission released: active tasks endpoint should show 0
            JsonNode active = fetchActiveTasks();
            assertThat(active).as("active tasks endpoint must be available").isNotNull();
            assertThat(active.get("currentActiveTasks").asInt())
                    .as("admission must be released after task completion")
                    .isZero();
        } else {
            // R2 completed directly (no ask_user interrupt)
            assertThat(r2State).isEqualTo("TASK_STATE_COMPLETED");
        }
    }

    /**
     * §7.2 活跃任务查询接口.
     * Verify /v1/current_active_tasks returns valid data.
     *
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    @Test
    void activeTaskEndpoint_returnsValidData() throws Exception {
        JsonNode active = fetchActiveTasks();
        assertThat(active).isNotNull();
        assertThat(active.has("maxConcurrentTasks")).isTrue();
        assertThat(active.has("currentActiveTasks")).isTrue();
        assertThat(active.get("currentActiveTasks").asInt()).isGreaterThanOrEqualTo(0);
    }

    /**
     * §7.1 per-Task Agent 实例隔离 — 验证 per-task 模式下每次 A2A 请求创建独立 Agent.
     * Two sequential requests on different conversations should each work independently.
     *
     * @throws Exception if the test fails
     */
    @Test
    void perTaskAgent_separateConversations_independentExecution() throws Exception {
        String ctx1 = "ext-per-task-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctx2 = "ext-per-task-b-" + UUID.randomUUID().toString().substring(0, 8);
        // First conversation: balance query
        JsonNode r1 = sendA2AMessage(ctx1, "帮我查询账户余额");
        assertThat(extractState(r1)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        // Second conversation: product recommendation (different workflow)
        JsonNode r2 = sendA2AMessage(ctx2, "帮我推荐一些理财产品");
        assertThat(extractState(r2)).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(extractText(r2)).contains("339现管DXXJ-1339");
    }

    // ===== Admission control tests (require max-concurrent-tasks > 0) =====

    /**
     * §7.2 并发达到上限时拒绝新请求.
     * Fills every configured quota slot with blocking requests, then verifies
     * the request beyond the limit is rejected with HTTP 503. Adapts to the
     * deployed {@code max-concurrent-tasks} (capped at
     * {@link #MAX_AFFORDABLE_ADMISSION_LIMIT} — larger limits would need too
     * many concurrent real backend conversations for E2E).
     *
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    @Test
    void admissionLimitReached_returns503() throws Exception {
        int limit = affordableAdmissionLimit();
        Assumptions.assumeTrue(limit > 0,
                "requires the service to run with 0 < max-concurrent-tasks <= "
                        + MAX_AFFORDABLE_ADMISSION_LIMIT
                        + " (larger limits are too expensive for external E2E)");
        String ctxRejected = "ext-rejected-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = new ThreadPoolExecutor(limit, limit, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            // Hold every quota slot (recommend products takes time)
            List<CompletableFuture<JsonNode>> blockers = startBlockers(pool, limit, "ext-blocker-");
            // Deterministic full-quota barrier: poll the probe endpoint
            // instead of a fixed sleep that may fire before or after the window
            awaitQuotaFull(limit);
            // The request beyond the limit must be rejected with 503
            HttpResponse<String> rejectedResp = sendA2ARaw(ctxRejected, "帮我查询账户余额");
            assertThat(rejectedResp.statusCode())
                    .as("request beyond the limit must be rejected with 503 (max-concurrent-tasks=%d)",
                            limit)
                    .isEqualTo(503);
            awaitBlockersDone(blockers);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.3 并发额度满 — HTTP 503 with JSON error body.
     *
     * @throws Exception if the test fails
     */
    @Test
    void admissionLimitReached_hasJsonErrorBody() throws Exception {
        int limit = affordableAdmissionLimit();
        Assumptions.assumeTrue(limit > 0,
                "requires the service to run with 0 < max-concurrent-tasks <= "
                        + MAX_AFFORDABLE_ADMISSION_LIMIT
                        + " (larger limits are too expensive for external E2E)");
        String ctxRejected = "ext-rejected-body-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = new ThreadPoolExecutor(limit, limit, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<CompletableFuture<JsonNode>> blockers = startBlockers(pool, limit, "ext-blocker-body-");
            awaitQuotaFull(limit);
            HttpResponse<String> rejectedResp = sendA2ARaw(ctxRejected, "帮我查询账户余额");
            // Unconditional: with a full quota the rejection is expected, and
            // the body must carry the JSON error surface.
            assertThat(rejectedResp.statusCode())
                    .as("request beyond the limit must be rejected with 503 (max-concurrent-tasks=%d)",
                            limit)
                    .isEqualTo(503);
            JsonNode errorBody = MAPPER.readTree(rejectedResp.body());
            assertThat(errorBody.has("error"))
                    .as("rejection must carry a JSON error body")
                    .isTrue();
            awaitBlockersDone(blockers);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 INPUT_REQUIRED 续传被拒 — resume rejected when quota is full.
     *
     * @throws Exception if the test fails
     */
    @Test
    void inputRequired_resumeRejectedWhenQuotaFull() throws Exception {
        int limit = affordableAdmissionLimit();
        Assumptions.assumeTrue(limit > 0,
                "requires the service to run with 0 < max-concurrent-tasks <= "
                        + MAX_AFFORDABLE_ADMISSION_LIMIT
                        + " (larger limits are too expensive for external E2E)");
        String ctxResume = "ext-resume-" + UUID.randomUUID().toString().substring(0, 8);
        // Step 1: create a task that enters INPUT_REQUIRED
        JsonNode r1 = sendA2AMessage(ctxResume, "帮我推荐一些理财产品");
        assertThat(extractState(r1)).isEqualTo("TASK_STATE_COMPLETED");
        JsonNode r2 = sendA2AMessage(ctxResume, "购买1号产品，金额5000元");
        // The resume-rejection scenario needs the backend ask_user flow to
        // pause the task; skip explicitly when the backend completes directly.
        Assumptions.assumeTrue("TASK_STATE_INPUT_REQUIRED".equals(extractState(r2)),
                "requires the backend ask_user flow to pause the task in INPUT_REQUIRED");
        // Step 2: fill every quota slot with blockers on other conversations
        ExecutorService pool = new ThreadPoolExecutor(limit, limit, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<CompletableFuture<JsonNode>> blockers = startBlockers(pool, limit, "ext-resume-blocker-");
            awaitQuotaFull(limit);
            // Step 3: try to resume the INPUT_REQUIRED task — must be rejected
            // while the blockers hold the whole quota
            HttpResponse<String> resumeResp = sendA2ARaw(ctxResume,
                    "确认购买1号339现管DXXJ-1339，5000元，请继续执行");
            assertThat(resumeResp.statusCode())
                    .as("resume must be rejected with 503 while quota is full (max-concurrent-tasks=%d)",
                            limit)
                    .isEqualTo(503);
            awaitBlockersDone(blockers);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 额度泄漏防护（失败路径）— 任务 FAILED 后额度必须先释放.
     *
     * <p>对同一 conversationId 发起并发请求是确定性的失败触发器：会话已被第一个请求
     * 激活时，第二个请求会被 Agent 层拒绝（ConversationBusy → AgentExecutionException），
     * runtime 将其收敛为终态 {@code TASK_STATE_FAILED} 任务（HTTP 200），随后额度必须
     * 释放（探针归零）且服务可继续接入新请求 —— 锁定"失败路径无额度泄漏"不变量。
     *
     * <p>注：额度满时的 executor 侧拒绝（TOCTOU 窗口，同样产生 FAILED 任务）窗口为
     * 亚毫秒级，外部 E2E 无法稳定命中，由 runtime 仓的 executor 单元/集成测试覆盖。
     *
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    @Test
    void conversationBusy_concurrentRequest_taskFailed_quotaReleased() throws Exception {
        int rawLimit = rawAdmissionLimit();
        Assumptions.assumeTrue(rawLimit == -1 || rawLimit >= 2,
                "requires unlimited quota or max-concurrent-tasks >= 2 (with limit=1 the "
                        + "concurrent request is rejected at the controller pre-check with 503 "
                        + "before the conversation-busy failure can surface)");
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            HttpResponse<String> failedResp = null;
            JsonNode firstResult = null;
            for (int attempt = 1; attempt <= 3 && failedResp == null; attempt++) {
                String convId = "ext-busy-" + attempt + "-" + UUID.randomUUID().toString().substring(0, 8);
                // 1. Start a request on the conversation — it activates the session
                CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendA2AMessage(convId, "帮我查询账户余额");
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, pool);
                if (!awaitQuotaFull(1)) {
                    first.get(120, TimeUnit.SECONDS); // probe never saw it — settle and retry
                    continue;
                }
                // 2. A concurrent request on the SAME conversation is admitted
                // by the executor but rejected by the agent layer (session busy)
                HttpResponse<String> resp = sendA2ARaw(convId, "帮我查询账户余额");
                if (isFailedTask(resp)) {
                    failedResp = resp;
                    firstResult = first.get(120, TimeUnit.SECONDS);
                } else {
                    // The first request completed before the second arrived
                    // (session released) — settle and retry with a fresh conversation
                    first.get(120, TimeUnit.SECONDS);
                }
            }
            Assumptions.assumeTrue(failedResp != null,
                    "conversation-busy failure not triggered in 3 attempts "
                            + "(first request completed before the concurrent one arrived)");
            // 3. The busy request must surface as HTTP 200 + terminal FAILED task
            assertThat(failedResp.statusCode())
                    .as("agent-layer rejection must surface as HTTP 200 with a FAILED task")
                    .isEqualTo(200);
            JsonNode failedTask = MAPPER.readTree(failedResp.body()).at("/result/task");
            assertThat(failedTask.get("id")).as("failed response must carry a task").isNotNull();
            assertThat(failedTask.at("/status/state").asText())
                    .as("conversation-busy task must end in the FAILED terminal state")
                    .isEqualTo("TASK_STATE_FAILED");
            // 4. The first request completes normally
            assertThat(firstResult).isNotNull();
            assertThat(extractState(firstResult))
                    .as("the session-holding request must complete")
                    .isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
            // 5. Core invariant: the failure path released its quota — no leak
            awaitQuotaDrained();
            // 6. Service stays healthy and admits a fresh request
            String ctxAfter = "ext-busy-after-" + UUID.randomUUID().toString().substring(0, 8);
            JsonNode next = sendA2AMessage(ctxAfter, "帮我查询账户余额");
            assertThat(extractState(next))
                    .as("a new request must be admitted after the failed task released its quota")
                    .isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 活跃任务列表 — active tasks visible during execution.
     *
     * @throws Exception if the test fails
     */
    @Test
    void activeTaskEndpoint_showsTaskDuringExecution() throws Exception {
        String ctx = "ext-active-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            // Start a request in background
            CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AMessage(ctx, "帮我推荐一些理财产品");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool);
            // Poll until the running task registers on the endpoint (instead of
            // a fixed sleep that may miss the window or assert too early)
            int activeCount = 0;
            JsonNode active = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                active = fetchActiveTasks();
                if (active != null && active.path("currentActiveTasks").asInt(0) > 0) {
                    activeCount = active.get("currentActiveTasks").asInt();
                    break;
                }
                Thread.sleep(500);
            }
            assertThat(active).as("active tasks endpoint must be available").isNotNull();
            assertThat(activeCount)
                    .as("should show at least 1 active task during execution")
                    .isGreaterThanOrEqualTo(1);
            future.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * §7.2 任务异常终止后额度释放 — after task failure, admission is released.
     * Verify that after a task completes (success or failure), admission count returns to 0.
     *
     * @throws Exception if the test fails
     */
    @Test
    void taskCompletion_admissionReleased() throws Exception {
        String ctx = "ext-release-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode result = sendA2AMessage(ctx, "帮我查询账户余额");
        assertThat(extractState(result)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        // After task completes, admission should be released — unconditional:
        // a null endpoint response is itself a failure, not a reason to skip
        JsonNode active = fetchActiveTasks();
        assertThat(active).as("active tasks endpoint must be available").isNotNull();
        assertThat(active.get("currentActiveTasks").asInt())
                .as("admission must be released after task completion")
                .isZero();
    }

    // ── Custom REST entry point (DFX-002 admission gate coverage) ──────

    /**
     * Verify custom REST blocking balance query completes successfully.
     *
     * @throws Exception if the test fails
     */
    @Test
    void customRest_blocking_balanceQuery_completesSuccessfully() throws Exception {
        Assumptions.assumeTrue(customRestEnabled(), "Custom REST endpoint not configured");
        String convId = "custom-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> resp = sendCustomRestBlocking(convId, "帮我查询账户余额");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("task_id")).isTrue();
        assertThat(body.get("context_id").asText()).isEqualTo(convId);
        assertThat(body.get("state").asText()).isEqualTo("TASK_STATE_COMPLETED");
    }

    /**
     * Verify custom REST streaming balance query reaches a terminal state.
     *
     * @throws Exception if the test fails
     */
    @Test
    void customRest_streaming_balanceQuery_terminalState() throws Exception {
        Assumptions.assumeTrue(customRestEnabled(), "Custom REST endpoint not configured");
        String convId = "custom-e2e-stream-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<java.io.InputStream> resp = http.send(
                HttpRequest.newBuilder(URI.create(customRestUrl(convId)))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"帮我查询账户余额\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        String terminalState = readSseTerminalState(resp.body(), Duration.ofSeconds(90));
        assertThat(terminalState).as("stream must reach a terminal state")
                .isIn("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_INPUT_REQUIRED");
    }

    // ── Push notification callback endpoint ────────────────────────────

    /**
     * Verify push notification callback endpoint is alive.
     *
     * @throws Exception if the test fails
     */
    @Test
    void pushNotificationCallback_endpointAlive() throws Exception {
        Assumptions.assumeTrue(pushNotificationEnabled(), "Push notifications not configured");
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a/push-notifications/callback"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isIn(400, 404, 500);
        assertThat(resp.statusCode()).isNotEqualTo(404);
    }

    // --- helpers ---

    /**
     * Upper bound for the adaptive admission tests: filling the quota needs
     * {@code limit} concurrent real backend conversations, so limits above
     * this bound are too slow/expensive for external E2E and are skipped.
     */
    private static final int MAX_AFFORDABLE_ADMISSION_LIMIT = 8;

    /**
     * Checks whether an admission limit is configured on the live service.
     *
     * @return {@code true} if {@code max-concurrent-tasks > 0} is detected,
     *         {@code false} if no limit is configured or the probe fails
     */
    private boolean admissionLimitConfigured() {
        try {
            JsonNode active = fetchActiveTasks();
            return active != null && active.path("maxConcurrentTasks").asInt(-1) > 0;
        } catch (Exception e) {
            // safe default: treat probe failure as "no limit configured"
            return false;
        }
    }

    /**
     * Returns the deployed admission limit when it is usable by the adaptive
     * admission tests ({@code 0 < limit <= MAX_AFFORDABLE_ADMISSION_LIMIT}),
     * otherwise {@code 0} (unlimited, unreachable, or unaffordably large).
     *
     * @return the affordable admission limit, or {@code 0} if not usable
     */
    private int affordableAdmissionLimit() {
        try {
            JsonNode active = fetchActiveTasks();
            if (active == null) {
                return 0;
            }
            int limit = active.path("maxConcurrentTasks").asInt(-1);
            return (limit > 0 && limit <= MAX_AFFORDABLE_ADMISSION_LIMIT) ? limit : 0;
        } catch (Exception e) {
            // safe default: treat probe failure as "unlimited or unreachable"
            return 0;
        }
    }

    /**
     * Returns the raw deployed {@code maxConcurrentTasks} value: {@code -1}
     * (unlimited), a positive limit, or {@code 0} when the probe is
     * unreachable.
     *
     * @return the raw admission limit, or {@code 0} if the probe is unreachable
     */
    private int rawAdmissionLimit() {
        try {
            JsonNode active = fetchActiveTasks();
            if (active == null) {
                return 0;
            }
            int limit = active.path("maxConcurrentTasks").asInt(-1);
            return limit == 0 ? 0 : limit;
        } catch (Exception e) {
            // safe default: treat probe failure as "unreachable"
            return 0;
        }
    }

    /**
     * Starts {@code count} blocking conversations (product recommendation is
     * a slow real flow) to hold the admission quota.
     *
     * @param pool   the executor service to run the blockers on
     * @param count  the number of blocking conversations to start
     * @param prefix the prefix for the conversation IDs
     * @return the list of blocker futures
     */
    private List<CompletableFuture<JsonNode>> startBlockers(ExecutorService pool, int count, String prefix) {
        List<CompletableFuture<JsonNode>> blockers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ctx = prefix + i + "-" + UUID.randomUUID().toString().substring(0, 8);
            blockers.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return sendA2AMessage(ctx, "帮我推荐一些理财产品");
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pool));
        }
        return blockers;
    }

    /**
     * Polls the active-tasks probe until the quota is full (deterministic
     * replacement for a fixed sleep). Returns true when the target count was
     * observed, false on timeout — callers decide whether to skip or fail.
     *
     * @param limit the target active-task count to observe
     * @return {@code true} if the quota was observed as full, {@code false} on timeout
     * @throws Exception if the polling is interrupted
     */
    private boolean awaitQuotaFull(int limit) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode active = fetchActiveTasks();
            if (active != null && active.path("currentActiveTasks").asInt(0) >= limit) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /**
     * Awaits all blockers and asserts each completed (or paused) cleanly.
     *
     * @param blockers the list of blocker futures to await
     * @throws Exception if a blocker future fails or times out
     */
    private void awaitBlockersDone(List<CompletableFuture<JsonNode>> blockers) throws Exception {
        for (CompletableFuture<JsonNode> blocker : blockers) {
            JsonNode result = blocker.get(120, TimeUnit.SECONDS);
            assertThat(extractState(result)).isIn("TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED");
        }
    }

    /**
     * Polls the probe until the active count drains to zero.
     *
     * @throws Exception if the polling is interrupted or the quota does not drain in time
     */
    private void awaitQuotaDrained() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode active = fetchActiveTasks();
            if (active != null && active.path("currentActiveTasks").asInt(0) == 0) {
                return;
            }
            Thread.sleep(300);
        }
        JsonNode active = fetchActiveTasks();
        assertThat(active).as("active tasks endpoint must be available").isNotNull();
        assertThat(active.get("currentActiveTasks").asInt())
                .as("quota must drain to zero after terminal tasks (no leak, failure path included)")
                .isZero();
    }

    /**
     * A terminal FAILED task response: HTTP 200 with a task whose state is
     * TASK_STATE_FAILED.
     *
     * @param resp the HTTP response to check
     * @return {@code true} if the response represents a failed task
     */
    private boolean isFailedTask(HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            return false;
        }
        try {
            return "TASK_STATE_FAILED".equals(
                    MAPPER.readTree(resp.body()).at("/result/task/status/state").asText(""));
        } catch (Exception e) {
            // safe default: treat parse failure as "not a failed task"
            return false;
        }
    }

    /**
     * Sends an A2A SendMessage request and returns the parsed JSON response.
     *
     * @param contextId the conversation context ID
     * @param text      the message text
     * @return the parsed JSON response
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    private JsonNode sendA2AMessage(String contextId, String text) throws Exception {
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "SendMessage")
                .set("params", MAPPER.createObjectNode()
                        .set("message", MAPPER.createObjectNode()
                                .put("role", "ROLE_USER")
                                .put("messageId", UUID.randomUUID().toString())
                                .put("contextId", contextId)
                                .set("parts", MAPPER.createArrayNode()
                                        .add(MAPPER.createObjectNode()
                                                .put("kind", "text")
                                                .put("text", text))))));
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
                        .header("Content-Type", "application/json")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("HTTP status for contextId=%s", contextId)
                .isIn(200, 503);
        return MAPPER.readTree(resp.body());
    }

    /**
     * Extracts the task state from an A2A JSON response.
     *
     * @param response the JSON response
     * @return the task state string, or empty string if not found
     */
    private String extractState(JsonNode response) {
        return response.at("/result/task/status/state").asText("");
    }

    /**
     * Extracts the text content from an A2A JSON response.
     *
     * @param response the JSON response
     * @return the text content, or empty string if not found
     */
    private String extractText(JsonNode response) {
        JsonNode parts = response.at("/result/task/artifacts/0/parts/0/text");
        return parts.isMissingNode() ? "" : parts.asText("");
    }

    private record StreamResult(String terminalState, String echoedContextId) {
    }

    /**
     * Sends an A2A SendStreamingMessage request and reads SSE events to
     * extract the terminal state and echoed context ID.
     *
     * @param contextId the conversation context ID
     * @param text      the message text
     * @return the streaming result with terminal state and echoed context ID
     * @throws Exception if the HTTP request or SSE reading fails
     */
    private StreamResult sendA2AStreamRaw(String contextId, String text) throws Exception {
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "SendStreamingMessage")
                .set("params", MAPPER.createObjectNode()
                        .set("message", MAPPER.createObjectNode()
                                .put("role", "ROLE_USER")
                                .put("messageId", UUID.randomUUID().toString())
                                .put("contextId", contextId)
                                .set("parts", MAPPER.createArrayNode()
                                        .add(MAPPER.createObjectNode()
                                                .put("kind", "text")
                                                .put("text", text))))));
        HttpResponse<java.io.InputStream> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode())
                .as("SendStreamingMessage must return 200 for contextId=%s", contextId)
                .isEqualTo(200);
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis() * 3 / 4;
        String echoedCtx = "";
        String terminalState = "";
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    Thread.sleep(30);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode event = MAPPER.readTree(data);
                    JsonNode statusUpdate = event.at("/result/statusUpdate");
                    if (!statusUpdate.isMissingNode()) {
                        String ctx = statusUpdate.path("contextId").asText("");
                        if (!ctx.isEmpty() && echoedCtx.isEmpty()) {
                            echoedCtx = ctx;
                        }
                        String state = statusUpdate.path("status").path("state").asText("");
                        if ("TASK_STATE_COMPLETED".equals(state)
                                || "TASK_STATE_FAILED".equals(state)
                                || "TASK_STATE_CANCELED".equals(state)
                                || "TASK_STATE_INPUT_REQUIRED".equals(state)) {
                            terminalState = state;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // best-effort: skip malformed SSE events
                }
            }
        }
        return new StreamResult(terminalState, echoedCtx);
    }

    /**
     * Fetches the current active tasks from the monitoring endpoint.
     *
     * @return the parsed JSON response, or {@code null} if the endpoint is unavailable
     */
    private JsonNode fetchActiveTasks() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/current_active_tasks"))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return MAPPER.readTree(resp.body());
            }
        } catch (Exception e) {
            // best-effort probe: endpoint may not be available in all configurations
        }
        return null;
    }

    /**
     * Sends a raw A2A SendMessage request and returns the HTTP response.
     *
     * @param contextId the conversation context ID
     * @param text      the message text
     * @return the raw HTTP response
     * @throws Exception if the HTTP request fails
     */
    private HttpResponse<String> sendA2ARaw(String contextId, String text) throws Exception {
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "SendMessage")
                .set("params", MAPPER.createObjectNode()
                        .set("message", MAPPER.createObjectNode()
                                .put("role", "ROLE_USER")
                                .put("messageId", UUID.randomUUID().toString())
                                .put("contextId", contextId)
                                .set("parts", MAPPER.createArrayNode()
                                        .add(MAPPER.createObjectNode()
                                                .put("kind", "text")
                                                .put("text", text))))));
        return http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
                        .header("Content-Type", "application/json")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Checks whether the custom REST endpoint is enabled.
     *
     * @return {@code true} if the custom REST endpoint is available
     */
    private boolean customRestEnabled() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(customRestUrl("__probe__")))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"probe\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() != 404;
        } catch (Exception e) {
            // safe default: treat connection failure as "not enabled"
            return false;
        }
    }

    /**
     * Checks whether push notifications are enabled.
     *
     * @return {@code true} if push notifications are configured
     */
    private boolean pushNotificationEnabled() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a/push-notifications/callback"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() != 404;
        } catch (Exception e) {
            // safe default: treat connection failure as "not enabled"
            return false;
        }
    }

    /**
     * Builds the custom REST URL for a given conversation.
     *
     * @param conversationId the conversation ID
     * @return the full URL for the custom REST endpoint
     */
    private String customRestUrl(String conversationId) {
        return BASE_URL + "/v1/test/agents/test/conversations/" + conversationId;
    }

    /**
     * Sends a blocking request to the custom REST endpoint.
     *
     * @param conversationId the conversation ID
     * @param text           the message text
     * @return the raw HTTP response
     * @throws Exception if the HTTP request fails
     */
    private HttpResponse<String> sendCustomRestBlocking(String conversationId, String text) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(customRestUrl(conversationId)))
                        .header("Content-Type", "application/json")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"message\":\"" + text + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Reads SSE events from an input stream to extract the terminal state.
     *
     * @param body    the input stream of SSE events
     * @param timeout the maximum time to wait for a terminal state
     * @return the terminal state string, or empty string if not reached
     * @throws Exception if reading the stream fails
     */
    private String readSseTerminalState(java.io.InputStream body, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    Thread.sleep(30);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode event = MAPPER.readTree(data);
                    JsonNode state = event.at("/status/state");
                    if (!state.isMissingNode()) {
                        String s = state.asText("");
                        if ("TASK_STATE_COMPLETED".equals(s) || "TASK_STATE_FAILED".equals(s)
                                || "TASK_STATE_CANCELED".equals(s) || "TASK_STATE_INPUT_REQUIRED".equals(s)) {
                            return s;
                        }
                    }
                    JsonNode taskState = event.at("/task/status/state");
                    if (!taskState.isMissingNode()) {
                        String s = taskState.asText("");
                        if ("TASK_STATE_COMPLETED".equals(s) || "TASK_STATE_FAILED".equals(s)
                                || "TASK_STATE_CANCELED".equals(s) || "TASK_STATE_INPUT_REQUIRED".equals(s)) {
                            return s;
                        }
                    }
                } catch (Exception ignored) {
                    // best-effort: skip malformed SSE events
                }
            }
        }
        return "";
    }
}
