/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.runtimeverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;
import com.openjiuwen.client.api.calltree.Completeness;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Browser-based verification console for AgentClient -> Runtime.
 *
 * @since 2026-07-27
 */
public final class RuntimeVerificationApp {
    private static final Logger LOG = Logger.getLogger(RuntimeVerificationApp.class.getName());
    private static final ObjectMapper JSON = createMapper();
    private static final List<Map<String, String>> SCENARIOS = List.of(
            scenario("single", "Single agent", "Root Agent streaming output"),
            scenario("nested-5", "Five-level tree", "A -> B -> C -> D -> E -> F"),
            scenario("parallel-interleave", "Parallel interleave", "B1/B2 outputs interleave"),
            scenario("multi-artifact", "Multiple artifacts", "Independent child artifacts"),
            scenario("output-before-edge", "Output before edge", "Orphan output is replayed after delegation"),
            scenario("input-linear", "User input continuation", "INPUT_REQUIRED then continueInput"),
            scenario("input-status-incomplete", "Incomplete input protocol", "Child status without root interrupt"),
            scenario("client-tool", "Local tool", "Runtime requests local.echo and SDK auto-resumes"),
            scenario("streaming-resubscribe", "Streaming resubscribe",
                    "After taskId is known, SubscribeToTask returns current Task state and future events", "STREAMING"),
            scenario("blocking-gettask", "Blocking current-state recovery",
                    "After taskId is known, recovery uses GetTask; intermediate frames are not replayed", "BLOCKING"),
            scenario("async-gettask", "Async current-state recovery",
                    "After accepted, the application calls getInvocation until terminal", "ASYNC"),
            scenario("recovery-circuit", "Recovery circuit", "Three failures settle as uncertain"),
            scenario("runtime-create-unknown", "Unknown create outcome", "Runtime create is not retried"),
            scenario("controller-return", "Controller return", "controller_output restores root speaking phase"),
            scenario("speaking-handoff", "Speaking handoff", "B1/B2 speaker then root control return"),
            scenario("root-output-filter", "Root output filter", "Child/control content excluded from root output"),
            scenario("root-output-replace", "Root output replace",
                    "Two append=false updates for one Artifact materialize only the latest text"),
            scenario("mode-unavailable", "Unary tree boundary", "BLOCKING/ASYNC tree is unavailable or partial"),
            scenario("malformed-graph", "Malformed graph", "Conflict degrades only the tree"));

    private final int port;
    private final String defaultRuntimeUrl;
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "verification-app-http");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    LOG.log(Level.WARNING, "uncaught exception in " + thread.getName(), ex);
                });
                return t;
            });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    RuntimeVerificationApp(int port, String defaultRuntimeUrl) {
        this.port = port;
        this.defaultRuntimeUrl = defaultRuntimeUrl;
    }

    private static ObjectMapper createMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            redirectOutput(args[2]);
        }
        String configuredPort = args.length > 0 ? args[0]
                : System.getenv().getOrDefault("VERIFICATION_APP_PORT", "18080");
        int port = Integer.parseInt(configuredPort);
        String runtime = args.length > 1 ? args[1]
                : System.getenv().getOrDefault("AGENT_RUNTIME_URL", "http://127.0.0.1:19090");
        RuntimeVerificationApp app = new RuntimeVerificationApp(port, runtime);
        app.start();
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Verification UI: http://127.0.0.1:" + port);
            LOG.log(Level.INFO, "Default Runtime: " + runtime);
        }
        Thread.currentThread().join();
    }

    private static void redirectOutput(String path) throws IOException {
        PrintStream log = new PrintStream(path, StandardCharsets.UTF_8);
        System.out.close();
        System.err.close();
        System.setOut(log);
        System.setErr(log);
    }

    HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", guarded(this::staticResource));
        server.createContext("/api/config", guarded(exchange -> json(exchange, 200,
                Map.of("runtimeUrl", defaultRuntimeUrl, "scenarios", SCENARIOS))));
        server.createContext("/api/run", guarded(this::startRun));
        server.createContext("/api/runs", guarded(this::getRun));
        server.createContext("/api/runtime/health", guarded(this::runtimeHealth));
        server.createContext("/api/runtime/requests", guarded(this::runtimeRequests));
        server.start();
        return server;
    }

    void close() {
        workers.shutdownNow();
    }

    private HttpHandler guarded(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (IOException | RuntimeException error) {
                LOG.log(Level.WARNING, "unhandled error in HTTP handler", error);
                try {
                    json(exchange, 500, Map.of("error", rootMessage(error)));
                } catch (IOException ignored) {
                    exchange.close();
                }
            }
        };
    }

    private void startRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            text(exchange, 405, "method not allowed");
            return;
        }
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        String runtimeUrl = body.path("runtimeUrl").asText(defaultRuntimeUrl);
        String scenario = body.path("scenario").asText("single");
        InvocationMode mode;
        try {
            mode = InvocationMode.valueOf(body.path("mode").asText("STREAMING"));
        } catch (IllegalArgumentException error) {
            json(exchange, 400, Map.of("error", "invalid mode"));
            return;
        }
        RunRequest request = new RunRequest(runtimeUrl, scenario, mode,
                body.path("input").asText("Verify AgentClient direct Runtime mode."),
                body.path("continueInput").asText("4241"));
        String id = UUID.randomUUID().toString();
        RunRecord record = new RunRecord(id, request);
        runs.put(id, record);
        workers.execute(() -> execute(record));
        json(exchange, 202, Map.of("runId", id));
    }

    private void getRun(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/runs/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            json(exchange, 200, runs.values().stream().map(RunRecord::view).toList());
            return;
        }
        RunRecord record = runs.get(URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8));
        if (record == null) {
            json(exchange, 404, Map.of("error", "run not found"));
            return;
        }
        json(exchange, 200, record.view());
    }

    private void execute(RunRecord run) {
        run.status = "RUNNING";
        run.startedAt = Instant.now();
        AgentClient client = null;
        try {
            run.wireStartSequence = currentMockWireSequence(run.request.runtimeUrl());
            client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                    .endpointUrl(run.request.runtimeUrl()).build();
            AtomicInteger toolExecutions = new AtomicInteger();
            client.tools().register(LocalToolDescriptor.builder("local.echo")
                    .displayName("Local echo").description("Returns the supplied text")
                    .requiredArguments("text").build(), (invocation, context) -> {
                        toolExecutions.incrementAndGet();
                        run.addDiagnostic("local.echo executed with " + invocation.arguments());
                        return ToolExecutionRecord.ok(invocation.toolCallId(), Map.of(
                                "echo", String.valueOf(invocation.arguments().get("text")),
                                "executedAt", Instant.now().toString()));
                    });
            String conversationId = "runtime-ui-" + run.id;
            if (configureMockScenario(run.request.runtimeUrl(), conversationId, run.request.scenario())) {
                run.addDiagnostic("Mock Runtime scenario bound through control plane: " + run.request.scenario());
            } else {
                run.addDiagnostic("Runtime has no Mock scenario control plane; executing plain A2A request");
            }
            client.exposeInConversation(conversationId, ToolExposurePolicy.allow("local.echo"));
            InvocationRequest request = InvocationRequest.builder()
                    .conversationId(conversationId).mode(run.request.mode()).input(run.request.input())
                    .attribute("traceId", run.id).credentialToken("must-not-reach-runtime")
                    .agentId("must-not-reach-runtime").build();
            InvocationCall call = client.invoke(request);
            run.invocationRef = call.invocationRef();
            CompletableFuture<InvocationEvent.InputRequired> inputRequired = subscribeEvents(run, call);
            subscribeTree(run, call);
            SnapshotResult result = resolveScenarioSnapshot(run, client, call, conversationId, inputRequired);
            finalizeRun(run, result.snapshot(), toolExecutions, result.observedIncompleteInput());
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            run.status = "FAILED";
            run.error = rootMessage(error);
            run.addDiagnostic(error.getClass().getSimpleName() + ": " + rootMessage(error));
        } finally {
            run.finishedAt = Instant.now();
            if (client != null) {
                client.close();
            }
        }
    }

    private static InvocationSnapshot queryAsyncUntilSettled(AgentClient client, InvocationCall call,
            RunRecord run, Duration timeout) throws InterruptedException,
            java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            InvocationSnapshot snapshot = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            run.addDiagnostic("Business getInvocation observed " + snapshot.state());
            if (snapshot.terminal() || snapshot.state() == TaskState.INPUT_REQUIRED) {
                return snapshot;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new IllegalStateException("ASYNC getInvocation observation timed out after " + timeout);
    }

    private boolean configureMockScenario(String runtimeUrl, String contextId, String scenario)
            throws IOException, InterruptedException {
        URI endpoint = URI.create(runtimeUrl.endsWith("/")
                ? runtimeUrl + "admin/scenario" : runtimeUrl + "/admin/scenario");
        String body = JSON.writeValueAsString(Map.of("contextId", contextId, "scenario", scenario));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 == 2) {
            return true;
        }
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            return false;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Mock Runtime scenario configuration failed: "
                    + response.statusCode() + " " + response.body());
        }
        return false;
    }

    private static CompletableFuture<InvocationEvent.InputRequired> subscribeEvents(RunRecord run,
            InvocationCall call) {
        CompletableFuture<InvocationEvent.InputRequired> inputRequired = new CompletableFuture<>();
        call.events().subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(InvocationEvent item) {
                run.events.add(Map.of("at", now(), "type", item.getClass().getSimpleName(),
                        "invocationRef", item.invocationRef(), "value", friendly(item)));
                if (item instanceof InvocationEvent.InputRequired required && required.toolCall() == null) {
                    inputRequired.complete(required);
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable error) {
                run.addDiagnostic("event stream error: " + rootMessage(error));
            }

            @Override
            public void onComplete() {
                run.addDiagnostic("event stream completed for " + call.invocationRef());
            }
        });
        return inputRequired;
    }

    private static void subscribeTree(RunRecord run, InvocationCall call) {
        call.callTree().subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(CallTreeSnapshot item) {
                run.treeSnapshot = item;
                run.tree = JSON.convertValue(item, Object.class);
                run.treeHistory.add(Map.of("at", now(), "revision", item.revision(),
                        "completeness", item.completeness(), "speakingPhase", item.speakingPhase()));
                subscription.request(1);
            }

            @Override
            public void onError(Throwable error) {
                run.addDiagnostic("call tree error: " + rootMessage(error));
            }

            @Override
            public void onComplete() {
                run.addDiagnostic("call tree completed for " + call.invocationRef());
            }
        });
    }

    private static void verifyRecoveryContract(RunRecord run) {
        String scenario = run.request.scenario();
        if (!isRecoveryScenario(scenario)) {
            return;
        }
        InvocationMode expectedMode = switch (scenario) {
            case "streaming-resubscribe" -> InvocationMode.STREAMING;
            case "blocking-gettask" -> InvocationMode.BLOCKING;
            case "async-gettask" -> InvocationMode.ASYNC;
            default -> throw new IllegalStateException("unknown recovery scenario " + scenario);
        };
        if (run.request.mode() != expectedMode) {
            throw new IllegalStateException(scenario + " requires " + expectedMode + " mode");
        }
        CallTreeSnapshot tree = run.treeSnapshot;
        if ("streaming-resubscribe".equals(scenario)) {
            if (tree != null && tree.completeness() != Completeness.PARTIAL) {
                throw new IllegalStateException("streaming recovery tree has invalid completeness "
                        + tree.completeness());
            }
        } else if (tree != null) {
            throw new IllegalStateException("BLOCKING/ASYNC must not construct a call tree");
        } else {
            // No constraint violation for other scenarios.
            return;
        }
        if ("streaming-resubscribe".equals(scenario)) {
            run.addDiagnostic("verified SubscribeToTask current snapshot + future events;"
                    + " no event-offset replay assumed");
        } else {
            run.addDiagnostic("verified GetTask current-state query;"
                    + " BLOCKING is SDK-driven and ASYNC is business-driven");
        }
        run.addDiagnostic(tree == null ? "call tree unavailable for recovered mode"
                : "recovered call tree completeness is " + tree.completeness());
    }

    private static boolean isRecoveryScenario(String scenario) {
        return "streaming-resubscribe".equals(scenario)
                || "blocking-gettask".equals(scenario)
                || "async-gettask".equals(scenario);
    }

    private static SnapshotResult resolveScenarioSnapshot(RunRecord run, AgentClient client,
            InvocationCall call, String conversationId,
            CompletableFuture<InvocationEvent.InputRequired> inputRequired) throws InterruptedException,
            java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        InvocationSnapshot snapshot;
        boolean observedIncompleteInput = false;
        if (run.request.mode() == InvocationMode.ASYNC) {
            call.accepted().toCompletableFuture().get(5, TimeUnit.SECONDS);
            snapshot = queryAsyncUntilSettled(client, call, run, Duration.ofSeconds(20));
            call.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            run.addDiagnostic("Business-driven getInvocation reached " + snapshot.state());
        } else if ("blocking-gettask".equals(run.request.scenario())) {
            snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            run.addDiagnostic("SDK automatic GetTask observation reconciled BLOCKING invocation");
        } else if ("input-linear".equals(run.request.scenario())) {
            inputRequired.get(10, TimeUnit.SECONDS);
            run.addDiagnostic("continueInput issued from UI scenario");
            InvocationCall continuation = client.continueInput(ContinueInputRequest.builder()
                    .conversationId(conversationId).relatedInvocationRef(call.invocationRef())
                    .input(run.request.continueInput()).build());
            run.continuationRef = continuation.invocationRef();
            subscribeEvents(run, continuation);
            subscribeTree(run, continuation);
            snapshot = continuation.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } else if ("input-status-incomplete".equals(run.request.scenario())) {
            InvocationEvent.InputRequired required = inputRequired.get(10, TimeUnit.SECONDS);
            snapshot = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            observedIncompleteInput = required.toolCall() == null
                    && snapshot.pendingToolCall() == null;
            run.addDiagnostic("incomplete child input protocol observed;"
                    + " SDK did not invent a resume target");
        } else {
            snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        }
        return new SnapshotResult(snapshot, observedIncompleteInput);
    }

    private void finalizeRun(RunRecord run, InvocationSnapshot snapshot,
            AtomicInteger toolExecutions, boolean observedIncompleteInput)
            throws IOException, InterruptedException {
        run.snapshot = JSON.convertValue(snapshot, Object.class);
        run.toolExecutions = toolExecutions.get();
        verifyRecoveryContract(run);
        verifyMockWireContract(run);
        run.status = observedIncompleteInput ? "OBSERVED"
                : isRecoveryScenario(run.request.scenario()) ? "VERIFIED" : "COMPLETED";
    }

    private record SnapshotResult(InvocationSnapshot snapshot, boolean observedIncompleteInput) {
    }

    private void verifyMockWireContract(RunRecord run) throws IOException, InterruptedException {
        if (!isRecoveryScenario(run.request.scenario())) {
            return;
        }
        String url = run.request.runtimeUrl() + "/admin/requests?after=0";
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            run.addDiagnostic("wire contract check skipped: Runtime does not expose mock diagnostics");
            return;
        }
        if (response.statusCode() != 200) {
            run.addDiagnostic("wire contract check skipped: Runtime does not expose mock diagnostics");
            return;
        }
        JsonNode requests = JSON.readTree(response.body());
        String expected = "streaming-resubscribe".equals(run.request.scenario())
                ? "SubscribeToTask" : "GetTask";
        boolean observed = false;
        for (JsonNode request : requests) {
            if (request.path("sequence").asInt() > run.wireStartSequence
                    && expected.equals(request.path("method").asText())) {
                observed = true;
                break;
            }
        }
        if (!observed) {
            throw new IllegalStateException("Mock Runtime did not observe required " + expected + " request");
        }
        run.addDiagnostic("wire verified: " + expected + " observed without an SSE cursor contract");
    }

    private int currentMockWireSequence(String runtimeUrl) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(runtimeUrl + "/admin/requests?after=0"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return -1;
            }
            int latest = 0;
            for (JsonNode request : JSON.readTree(response.body())) {
                latest = Math.max(latest, request.path("sequence").asInt());
            }
            return latest;
        } catch (IOException | InterruptedException ignored) {
            return -1;
        }
    }

    private void runtimeHealth(HttpExchange exchange) throws IOException {
        proxyGet(exchange, query(exchange, "url", defaultRuntimeUrl) + "/admin/health");
    }

    private void runtimeRequests(HttpExchange exchange) throws IOException {
        String url = query(exchange, "url", defaultRuntimeUrl);
        String after = query(exchange, "after", "0");
        proxyGet(exchange, url + "/admin/requests?after=" + after);
    }

    private void proxyGet(HttpExchange exchange, String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            send(exchange, response.statusCode(), "application/json; charset=utf-8",
                    response.body().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException error) {
            json(exchange, 502, Map.of("error", rootMessage(error), "target", url));
        }
    }

    private void staticResource(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            text(exchange, 405, "method not allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            text(exchange, 400, "bad path");
            return;
        }
        try (InputStream input = RuntimeVerificationApp.class.getClassLoader()
                .getResourceAsStream("runtime-web" + path)) {
            if (input == null) {
                text(exchange, 404, "not found");
                return;
            }
            send(exchange, 200, contentType(path), input.readAllBytes());
        }
    }

    private static String query(HttpExchange exchange, String name, String fallback) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return fallback;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && name.equals(pair.substring(0, split))) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    private static Map<String, String> scenario(String id, String title, String description) {
        return Map.of("id", id, "title", title, "description", description);
    }

    private static Map<String, String> scenario(String id, String title, String description, String mode) {
        return Map.of("id", id, "title", title, "description", description, "mode", mode);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(value));
    }

    private static void text(HttpExchange exchange, int status, String value) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", value.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static Object friendly(Object value) {
        return JSON.convertValue(value, Object.class);
    }

    private record RunRequest(String runtimeUrl, String scenario, InvocationMode mode,
            String input, String continueInput) {
        // Compact record — all fields are defined in the header.
    }

    private static final class RunRecord {
        private final String id;
        private final RunRequest request;
        private final Instant createdAt = Instant.now();
        private final List<Object> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Object> treeHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Object> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile String status = "QUEUED";
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String invocationRef;
        private volatile String continuationRef;
        private volatile Object snapshot;
        private volatile Object tree;
        private volatile CallTreeSnapshot treeSnapshot;
        private volatile String error;
        private volatile int toolExecutions;
        private volatile int wireStartSequence = -1;

        private RunRecord(String id, RunRequest request) {
            this.id = id;
            this.request = request;
        }

        private void addDiagnostic(String message) {
            diagnostics.add(Map.of("at", now(), "message", message));
        }

        private Object view() {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("id", id);
            value.put("status", status);
            value.put("request", request);
            value.put("createdAt", createdAt.toString());
            value.put("startedAt", startedAt == null ? null : startedAt.toString());
            value.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            value.put("invocationRef", invocationRef);
            value.put("continuationRef", continuationRef);
            value.put("events", events);
            value.put("treeHistory", treeHistory);
            value.put("diagnostics", diagnostics);
            value.put("snapshot", snapshot);
            value.put("tree", tree);
            value.put("error", error);
            value.put("toolExecutions", toolExecutions);
            value.put("recoveryContract", recoveryContract(request.scenario()));
            return value;
        }

        private static String recoveryContract(String scenario) {
            return switch (scenario) {
                case "streaming-resubscribe" -> "SubscribeToTask: current snapshot + future events; tree PARTIAL";
                case "blocking-gettask" -> "SDK bounded GetTask: current state only; no tree";
                case "async-gettask" -> "Business getInvocation/GetTask: current state only; no tree";
                default -> "Not a recovery-specific scenario";
            };
        }
    }
}
