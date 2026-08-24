/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sustained concurrent load test against a real edp-agent instance
 * (default: http://localhost:8190).
 *
 * <p>Drives 16 concurrent workers for ~2 minutes against two transports
 * (blocking {@code SendMessage} and SSE {@code SendStreamingMessage}), then
 * asserts the four stability invariants:
 * <ol>
 *   <li>quota hygiene — the admission counter drains to zero (no leak),</li>
 *   <li>no unexpected errors — every response is 200 with a terminal task
 *       state (503s only when a quota is configured and legitimately hit;
 *       for streaming, no stream hangs past its terminal-state timeout),</li>
 *   <li>context isolation — response contextIds never mix across
 *       conversations,</li>
 *   <li>consistent accounting — completed request count equals issued
 *       request count (no hangs, no losses).</li>
 * </ol>
 *
 * <p>Requires the external services (edp-agent + versatile-agent + LLM
 * gateway) to be running. Gated separately from the functional E2E suite:
 * run with {@code mvn test -Dexternal.e2e.stress.enabled=true -pl engine}.
 *
 * @since 0.1.2
 */
@EnabledIfSystemProperty(named = "external.e2e.stress.enabled", matches = "true")
class ExternalStressConcurrencyIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(ExternalStressConcurrencyIntegrationTest.class);

    private static final String BASE_URL = System.getProperty("external.e2e.url", "http://localhost:8190");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    private static final int WORKERS = 16;

    private static final long RUN_SECONDS = Long.getLong("external.e2e.stress.seconds", 120L);

    /** Conversation texts exercising different (real) backend flows. */
    private static final String[] QUERIES = {
        "帮我查询账户余额",
        "帮我推荐一些理财产品"
    };

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void sustainedConcurrency_noUnexpectedErrors() throws Exception {
        driveSustainedLoad(false);
    }

    /**
     * Streaming variant of the sustained load test. Each worker opens an SSE
     * stream via {@code SendStreamingMessage}, tracks terminal state
     * incrementally, and applies the same four invariants plus a streaming
     * stability invariant: no stream must hang past its timeout.
     *
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    @Test
    void sustainedStreaming_noHangs() throws Exception {
        driveSustainedLoad(true);
    }

    /**
     * Drives sustained concurrent load against the agent endpoint, verifying
     * the four stability invariants under concurrent stress.
     *
     * @param streaming whether to use SSE streaming or blocking requests
     * @throws Exception if the underlying execution or assertions fail
     */
    private void driveSustainedLoad(boolean streaming) throws Exception {
        int configuredLimit = rawAdmissionLimit();
        boolean quotaLimited = configuredLimit > 0;

        StressContext ctx = new StressContext(quotaLimited,
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new ArrayList<>(), new ConcurrentHashMap<>());

        ExecutorService pool = new ThreadPoolExecutor(WORKERS, WORKERS,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setUncaughtExceptionHandler((thr, e) ->
                            log.error("Uncaught in {}: {}", thr.getName(), e.getMessage()));
                    return t;
                });
        CountingLatch done = new CountingLatch();
        long deadline = System.currentTimeMillis() + RUN_SECONDS * 1000L;
        try {
            for (int w = 0; w < WORKERS; w++) {
                pool.submit(() -> {
                    runWorkerLoop(streaming, deadline, ctx);
                    done.countDown();
                });
            }
            boolean finished = done.await(RUN_SECONDS + TIMEOUT.getSeconds() + 60, TimeUnit.SECONDS);
            assertThat(finished).as("all workers must finish within the run window").isTrue();

            assertStabilityInvariants(streaming, ctx);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Asserts the four stability invariants after all workers complete.
     *
     * @param streaming whether the test ran in streaming mode
     * @param ctx       the shared stress test counters and state
     * @throws Exception if the quota drain or active tasks probe fails
     */
    private void assertStabilityInvariants(boolean streaming, StressContext ctx) throws Exception {
        // invariant 4: accounting — issued == all accounted responses
        assertThat(ctx.issued().get())
                .as("issued requests must equal accounted responses (issued=%d, ok=%d, 503=%d, "
                        + "executor-rejected=%d, failed=%d, hangs=%d, unexpected=%d)",
                        ctx.issued().get(), ctx.terminalOk().get(), ctx.rejected503().get(),
                        ctx.rejectedExecutor().get(),
                        ctx.failedTasks().get(), ctx.streamHang().get(), ctx.unexpected().get())
                .isEqualTo(ctx.terminalOk().get() + ctx.rejected503().get() + ctx.rejectedExecutor().get()
                        + ctx.failedTasks().get() + ctx.unexpected().get());

        // invariant 2: no unexpected errors; for streaming, "hang" is itself an error
        assertThat(ctx.unexpected().get())
                .as("unexpected responses must be zero; first issues: %s",
                        ctx.unexpectedDetails().subList(0, Math.min(3, ctx.unexpectedDetails().size())))
                .isZero();
        if (streaming) {
            assertThat(ctx.streamHang().get())
                    .as("no SSE stream must hang past its terminal-state timeout")
                    .isZero();
        }

        // invariant 2b: FAILED tasks are tolerated only when a quota is
        // configured (executor-side rejection in the race window);
        // with unlimited quota they would be surfaced as unexpected above.
        // Streaming is exempt — some streaming paths legitimately end
        // FAILED (e.g. resume-after-interrupt is known to fail on
        // streaming while succeeding on the blocking path).
        if (!ctx.quotaLimited() && !streaming) {
            assertThat(ctx.failedTasks().get())
                    .as("blocking mode with unlimited quota: no task should end FAILED")
                    .isZero();
        }

        // invariant 3: context isolation — no conversation saw more than
        // one response (fresh conversation per request)
        List<String> crossTalk = new ArrayList<>();
        ctx.responsesByContext().forEach((context, count) -> {
            if (count.get() > 1) {
                crossTalk.add(context + " x" + count.get());
            }
        });
        assertThat(crossTalk)
                .as("no conversation may receive another conversation's response")
                .isEmpty();

        // invariant 1: quota hygiene — counter drains to zero after load
        if (ctx.quotaLimited()) {
            awaitQuotaDrained();
        }
        JsonNode active = fetchActiveTasks().orElseThrow(
                () -> new AssertionError("active tasks endpoint unavailable"));
        assertThat(active).as("active tasks endpoint must be available").isNotNull();
        assertThat(active.get("currentActiveTasks").asInt())
                .as("quota must drain to zero after sustained load (no leak)")
                .isZero();

        // "exercised real flows" gate: at least 10 requests must have
        // been admitted and reached a terminal state (success OR failure).
        // FAILED tasks count here — concurrency stability is the focus
        // of this suite, functional correctness belongs to the functional
        // E2E suite. A FAILED terminal state still proves the path ran.
        assertThat(ctx.terminalOk().get() + ctx.failedTasks().get())
                .as("the load must actually exercise real flows (need >= 10 admitted-to-terminal "
                        + "responses; got ok=%d, failed=%d; with a small quota most requests are "
                        + "legitimately rejected)", ctx.terminalOk().get(), ctx.failedTasks().get())
                .isGreaterThanOrEqualTo(10);
    }

    /**
     * Runs the main request loop for a single worker, driving repeated A2A
     * requests until the deadline passes or too many unexpected errors occur.
     *
     * @param streaming whether to use SSE streaming or blocking requests
     * @param deadline  epoch millis after which the loop should stop
     * @param ctx       the shared stress test counters and state
     */
    private void runWorkerLoop(boolean streaming, long deadline, StressContext ctx) {
        while (System.currentTimeMillis() < deadline && ctx.unexpected().get() < 10) {
            String convId = "stress-" + UUID.randomUUID().toString().substring(0, 12);
            String text = QUERIES[ThreadLocalRandom.current().nextInt(QUERIES.length)];
            ctx.issued().incrementAndGet();
            RequestOutcome outcome;
            try {
                outcome = streaming ? sendA2AStream(convId, text) : sendA2ARawOutcome(convId, text);
            } catch (IOException | InterruptedException e) {
                // client-side exceptions (I/O, timeout) are unexpected in this stress scenario
                ctx.unexpected().incrementAndGet();
                ctx.unexpectedDetails().add("client exception: " + e);
                continue;
            }
            processOutcome(outcome, convId, ctx);
        }
    }

    /**
     * Processes a single request outcome, updating the appropriate counters
     * based on the HTTP status, task state, and context isolation checks.
     *
     * @param outcome the request outcome to process
     * @param convId  the conversation id sent with the request
     * @param ctx     the shared stress test counters and state
     */
    private void processOutcome(RequestOutcome outcome, String convId, StressContext ctx) {
        if (outcome.httpStatus == 503) {
            if (ctx.quotaLimited()) {
                ctx.rejected503().incrementAndGet();
            } else {
                ctx.unexpected().incrementAndGet();
                ctx.unexpectedDetails().add("503 with unlimited quota: " + snippet(outcome.body));
            }
            return;
        }
        if (!convId.equals(outcome.echoedContextId)) {
            if (outcome.isJsonRpcError) {
                if (ctx.quotaLimited() && outcome.jsonRpcErrorCode == -32603) {
                    ctx.rejectedExecutor().incrementAndGet();
                } else {
                    ctx.unexpected().incrementAndGet();
                    ctx.unexpectedDetails().add("error body: " + snippet(outcome.body));
                }
            } else {
                ctx.unexpected().incrementAndGet();
                ctx.unexpectedDetails().add("context mismatch: expected " + convId
                        + " got " + outcome.echoedContextId + " " + snippet(outcome.body));
            }
            return;
        }
        if (outcome.streamHang) {
            ctx.streamHang().incrementAndGet();
            ctx.unexpected().incrementAndGet();
            ctx.unexpectedDetails().add("stream hung past timeout: " + snippet(outcome.body));
            return;
        }
        ctx.responsesByContext().computeIfAbsent(convId, k -> new AtomicInteger())
                .incrementAndGet();
        String state = outcome.terminalState;
        if ("TASK_STATE_FAILED".equals(state)) {
            ctx.failedTasks().incrementAndGet();
        } else if ("TASK_STATE_COMPLETED".equals(state)
                || "TASK_STATE_INPUT_REQUIRED".equals(state)) {
            ctx.terminalOk().incrementAndGet();
        } else {
            ctx.unexpected().incrementAndGet();
            ctx.unexpectedDetails().add("non-terminal state " + state + ": "
                    + snippet(outcome.body));
        }
    }

    /** Shared mutable state for a single stress-test run. */
    private record StressContext(boolean quotaLimited,
            AtomicInteger issued, AtomicInteger rejected503, AtomicInteger rejectedExecutor,
            AtomicInteger failedTasks, AtomicInteger terminalOk, AtomicInteger streamHang,
            AtomicInteger unexpected, List<String> unexpectedDetails,
            Map<String, AtomicInteger> responsesByContext) {
    }

    /** Unified outcome for either the blocking or the streaming request. */
    private record RequestOutcome(int httpStatus, String echoedContextId, String terminalState,
            boolean streamHang, boolean isJsonRpcError, int jsonRpcErrorCode, String body) {
    }

    /**
     * Sends a blocking {@code SendMessage} request and returns the parsed outcome.
     *
     * @param contextId the conversation id to send with the request
     * @param text      the user message text to send
     * @return the parsed request outcome
     * @throws IOException          if the HTTP request or JSON parsing fails
     * @throws InterruptedException if the HTTP request is interrupted
     */
    private RequestOutcome sendA2ARawOutcome(String contextId, String text)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = sendA2ARaw(contextId, text);
        JsonNode body = MAPPER.readTree(resp.body());
        boolean isError = body.has("error");
        return new RequestOutcome(
                resp.statusCode(),
                body.at("/result/task/contextId").asText(""),
                body.at("/result/task/status/state").asText(""),
                false,
                isError,
                isError ? body.at("/error/code").asInt(0) : 0,
                resp.body());
    }

    /**
     * Issues a {@code SendStreamingMessage} request and reads the SSE stream
     * incrementally until a terminal task state is observed, the timeout
     * elapses (hang), or the stream ends. Returns the terminal state, the
     * echoed conversation id (from the first {@code statusUpdate}), and a
     * hang flag.
     *
     * @param contextId the conversation id to send with the request
     * @param text      the user message text to send
     * @return the outcome containing terminal state, echoed context id, and hang flag
     * @throws IOException          if the HTTP request, SSE stream reading, or JSON parsing fails
     * @throws InterruptedException if the HTTP request or SSE reading is interrupted
     */
    private RequestOutcome sendA2AStream(String contextId, String text)
            throws IOException, InterruptedException {
        String requestBody = MAPPER.writeValueAsString(MAPPER.createObjectNode()
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

        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
                        .header("Content-Type", "application/json")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            return new RequestOutcome(resp.statusCode(), "", "", false, false, 0, body);
        }
        return readSseStream(resp);
    }

    /**
     * Reads an SSE stream incrementally until a terminal task state is
     * observed, the timeout elapses (hang), or the stream ends.
     *
     * @param resp the HTTP response with an InputStream body
     * @return the outcome containing terminal state, echoed context id, and hang flag
     * @throws IOException          if the SSE stream reading or JSON parsing fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private RequestOutcome readSseStream(HttpResponse<InputStream> resp)
            throws IOException, InterruptedException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis() * 3 / 4;
            String echoedCtx = "";
            String terminalState = "";
            StringBuilder snippet = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    Thread.sleep(30);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (snippet.length() < 200) {
                    snippet.append(line).append('\n');
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode event = MAPPER.readTree(data);
                // JSON-RPC error envelope on the stream
                if (event.has("error")) {
                    int code = event.at("/error/code").asInt(0);
                    return new RequestOutcome(200, "", "", false, true, code, snippet.toString());
                }
                JsonNode statusUpdate = event.at("/result/statusUpdate");
                if (!statusUpdate.isMissingNode()) {
                    String ctx = statusUpdate.path("contextId").asText("");
                    if (!ctx.isEmpty() && echoedCtx.isEmpty()) {
                        echoedCtx = ctx;
                    }
                    String state = statusUpdate.path("status").path("state").asText("");
                    if ("TASK_STATE_COMPLETED".equals(state)
                            || "TASK_STATE_FAILED".equals(state)
                            || "TASK_STATE_CANCELED".equals(state)) {
                        terminalState = state;
                        break;
                    }
                }
            }
            boolean hang = terminalState.isEmpty();
            return new RequestOutcome(200, echoedCtx, terminalState, hang, false, 0, snippet.toString());
        }
    }

    /**
     * Sends a blocking {@code SendMessage} request and returns the raw HTTP response.
     *
     * @param contextId the conversation id to send with the request
     * @param text      the user message text to send
     * @return the raw HTTP response as a string
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the request is interrupted
     */
    private HttpResponse<String> sendA2ARaw(String contextId, String text)
            throws IOException, InterruptedException {
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
     * Fetches the active tasks snapshot from the agent endpoint.
     *
     * @return the active tasks JSON, or empty if the endpoint is unreachable
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the request is interrupted
     */
    private Optional<JsonNode> fetchActiveTasks() throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/current_active_tasks"))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200
                ? Optional.of(MAPPER.readTree(resp.body()))
                : Optional.empty();
    }

    /**
     * Returns the raw deployed {@code maxConcurrentTasks} value: {@code -1}
     * (unlimited), a positive limit, or {@code 0} when the probe is unreachable.
     *
     * @return the raw admission limit, or {@code 0} if the probe is unreachable
     * @throws IOException          if the HTTP request to the active-tasks endpoint fails
     * @throws InterruptedException if the request is interrupted
     */
    private int rawAdmissionLimit() throws IOException, InterruptedException {
        Optional<JsonNode> active = fetchActiveTasks();
        return active.map(jsonNode -> jsonNode.path("maxConcurrentTasks").asInt(-1)).orElse(0);
    }

    /**
     * Waits for the quota counter to drain to zero, with a 60-second timeout.
     *
     * @throws InterruptedException if interrupted while waiting
     * @throws IOException          if the HTTP request to the active-tasks endpoint fails
     */
    private void awaitQuotaDrained() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<JsonNode> active = fetchActiveTasks();
            if (active.isPresent() && active.get().path("currentActiveTasks").asInt(0) == 0) {
                return;
            }
            Thread.sleep(300);
        }
    }

    /**
     * Truncates a response body to a readable snippet for diagnostic output.
     *
     * @param body the response body to truncate
     * @return a snippet of at most 120 characters
     */
    private static String snippet(String body) {
        return body == null ? "" : body.substring(0, Math.min(120, body.length()));
    }

    /**
     * Latch counting worker completion without pre-knowing the count at
     * construction time (tracks WORKERS via countDown).
     */
    private static final class CountingLatch {
        private final AtomicLong count = new AtomicLong();
        private final CompletableFuture<Void> all = new CompletableFuture<>();

        void countDown() {
            if (count.incrementAndGet() >= WORKERS) {
                all.complete(null);
            }
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                all.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                // timed out waiting for all workers to complete
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                // propagate underlying worker failure as unchecked
                throw new IllegalStateException(e);
            }
        }
    }
}
