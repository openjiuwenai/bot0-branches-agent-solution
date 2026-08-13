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
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Browser-based verification console for AgentClient -> Runtime. */
public final class RuntimeVerificationApp {
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
            scenario("disconnect-replay", "Disconnect replay", "Subscribe from Last-Event-ID"),
            scenario("replay-duplicate", "Duplicate replay", "At-least-once replay is deduplicated"),
            scenario("cursor-expired", "Expired cursor", "Falls back to GetTask"),
            scenario("recovery-circuit", "Recovery circuit", "Three failures settle as uncertain"),
            scenario("runtime-create-unknown", "Unknown create outcome", "Runtime create is not retried"),
            scenario("controller-return", "Controller return", "controller_output restores root speaking phase"),
            scenario("speaking-handoff", "Speaking handoff", "B1/B2 speaker then root control return"),
            scenario("root-output-filter", "Root output filter", "Child/control content excluded from root output"),
            scenario("mode-unavailable", "Unary tree boundary", "BLOCKING/ASYNC tree is unavailable or partial"),
            scenario("malformed-graph", "Malformed graph", "Conflict degrades only the tree"));

    private final int port;
    private final String defaultRuntimeUrl;
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private RuntimeVerificationApp(int port, String defaultRuntimeUrl) {
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
        System.out.println("Verification UI: http://127.0.0.1:" + port);
        System.out.println("Default Runtime: " + runtime);
        Thread.currentThread().join();
    }

    private static void redirectOutput(String path) throws IOException {
        PrintStream log = new PrintStream(path, StandardCharsets.UTF_8);
        System.out.close();
        System.err.close();
        System.setOut(log);
        System.setErr(log);
    }

    private void start() throws IOException {
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
    }

    private HttpHandler guarded(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (Throwable error) {
                error.printStackTrace(System.err);
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
            client.exposeInConversation(conversationId, ToolExposurePolicy.allow("local.echo"));
            InvocationRequest request = InvocationRequest.builder()
                    .conversationId(conversationId).mode(run.request.mode()).input(run.request.input())
                    .attribute("scenario", run.request.scenario())
                    .attribute("traceId", run.id).credentialToken("must-not-reach-runtime")
                    .agentId("must-not-reach-runtime").build();
            InvocationCall call = client.invoke(request);
            run.invocationRef = call.invocationRef();
            CompletableFuture<InvocationEvent.InputRequired> inputRequired = subscribeEvents(run, call);
            subscribeTree(run, call);
            InvocationSnapshot snapshot;
            boolean observedIncompleteInput = false;
            if (run.request.mode() == InvocationMode.ASYNC) {
                call.accepted().toCompletableFuture().get(5, TimeUnit.SECONDS);
                snapshot = client.getInvocation(call.invocationRef()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                for (int i = 0; i < 4 && !snapshot.terminal(); i++) {
                    snapshot = client.getInvocation(call.invocationRef()).toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    run.addDiagnostic("GetTask observed " + snapshot.state());
                }
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
                snapshot = client.getInvocation(call.invocationRef()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                observedIncompleteInput = required.toolCall() == null && snapshot.pendingToolCall() == null;
                run.addDiagnostic("incomplete child input protocol observed; SDK did not invent a resume target");
            } else {
                snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            }
            run.snapshot = JSON.convertValue(snapshot, Object.class);
            run.toolExecutions = toolExecutions.get();
            run.status = observedIncompleteInput ? "OBSERVED" : snapshot.recovery() == null ? "COMPLETED" : "UNCERTAIN";
        } catch (Exception error) {
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
        } catch (Exception error) {
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
        private volatile String error;
        private volatile int toolExecutions;

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
            return value;
        }
    }
}
