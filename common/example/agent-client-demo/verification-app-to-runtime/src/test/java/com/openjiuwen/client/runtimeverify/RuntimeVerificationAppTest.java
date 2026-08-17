/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.runtimeverify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.mockruntime.MockRuntimeServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Test for RuntimeVerificationAppTest.
 *
 * @since 2026-07-27
 */
class RuntimeVerificationAppTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private HttpServer runtime;
    private HttpServer appServer;
    private RuntimeVerificationApp app;
    private String appUrl;
    private String runtimeUrl;

    @BeforeEach
    void start() throws Exception {
        runtime = new MockRuntimeServer(0).startServer();
        runtimeUrl = "http://127.0.0.1:" + runtime.getAddress().getPort();
        app = new RuntimeVerificationApp(0, runtimeUrl);
        appServer = app.start();
        appUrl = "http://127.0.0.1:" + appServer.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (appServer != null) {
            appServer.stop(0);
        }
        if (app != null) {
            app.close();
        }
        if (runtime != null) {
            runtime.stop(0);
        }
    }

    @Test
    void asyncScenarioUsesBusinessGetInvocationAndNoAttributesOrTree() throws Exception {
        JsonNode run = execute("async-gettask", "ASYNC");

        assertEquals("VERIFIED", run.path("status").asText());
        assertEquals("COMPLETED", run.path("snapshot").path("state").asText());
        assertTrue(run.path("diagnostics").toString().contains("Business getInvocation observed"));
        assertTrue(run.path("tree").isMissingNode() || run.path("tree").isNull());
        JsonNode requests = runtimeRequests();
        assertTrue(requests.toString().contains("GetTask"));
        for (JsonNode record : requests) {
            assertTrue(!record.path("body").path("params").path("metadata").has("attributes"));
        }
    }

    @Test
    void fiveLevelTreeAndClientToolScenariosAreActuallySelected() throws Exception {
        JsonNode tree = execute("nested-5", "STREAMING");
        assertEquals("COMPLETED", tree.path("status").asText());
        assertTrue(maxDepth(tree.path("tree").path("root")) >= 6);

        for (String mode : java.util.List.of("STREAMING", "BLOCKING", "ASYNC")) {
            JsonNode tool = execute("client-tool", mode);
            assertEquals("COMPLETED", tool.path("status").asText(), mode + ": " + tool);
            assertEquals(1, tool.path("toolExecutions").asInt(), mode + ": " + tool);
        }
    }

    @Test
    void concurrentInvocationsKeepIndependentRefsAndTrees() throws Exception {
        CompletableFuture<JsonNode> nested = CompletableFuture.supplyAsync(() -> uncheckedExecute(
                "nested-5", "STREAMING"));
        CompletableFuture<JsonNode> parallel = CompletableFuture.supplyAsync(() -> uncheckedExecute(
                "parallel-interleave", "STREAMING"));

        JsonNode nestedRun = nested.get();
        JsonNode parallelRun = parallel.get();
        assertEquals("COMPLETED", nestedRun.path("status").asText());
        assertEquals("COMPLETED", parallelRun.path("status").asText());
        assertTrue(!nestedRun.path("invocationRef").asText()
                .equals(parallelRun.path("invocationRef").asText()));
        assertTrue(!nestedRun.path("tree").path("root").path("key").path("taskId").asText()
                .equals(parallelRun.path("tree").path("root").path("key").path("taskId").asText()));
        assertTrue(maxDepth(nestedRun.path("tree").path("root")) >= 6);
        assertEquals(2, parallelRun.path("tree").path("root").path("children").size());
    }

    @Test
    void rootOutputReplaceScenarioMaterializesOnlyLatestArtifactValue() throws Exception {
        JsonNode run = execute("root-output-replace", "STREAMING");

        assertEquals("COMPLETED", run.path("status").asText());
        assertEquals("NEW_ROOT_TEXT", run.path("snapshot").path("outputText").asText());
        assertEquals("NEW_ROOT_TEXT", run.path("tree").path("root").path("artifacts").get(0)
                .path("parts").get(0).path("text").asText());
        assertEquals(2, java.util.stream.StreamSupport.stream(run.path("events").spliterator(), false)
                .filter(event -> "ContentDelta".equals(event.path("type").asText()))
                .count());
    }

    private JsonNode uncheckedExecute(String scenario, String mode) {
        try {
            return execute(scenario, mode);
        } catch (IOException | InterruptedException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    private JsonNode execute(String scenario, String mode) throws Exception {
        String request = JSON.writeValueAsString(java.util.Map.of("runtimeUrl", runtimeUrl,
                "scenario", scenario, "mode", mode, "input", "verify"));
        JsonNode accepted = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(appUrl + "/api/run"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        String runId = accepted.path("runId").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode run;
        do {
            run = JSON.readTree(HTTP.send(HttpRequest.newBuilder(
                    URI.create(appUrl + "/api/runs/" + runId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            if (!"QUEUED".equals(run.path("status").asText())
                    && !"RUNNING".equals(run.path("status").asText())) {
                return run;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not finish: " + run);
    }

    private JsonNode runtimeRequests() throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(
                URI.create(runtimeUrl + "/admin/requests?after=0")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static int maxDepth(JsonNode node) {
        int childDepth = 0;
        for (JsonNode child : node.path("children")) {
            childDepth = Math.max(childDepth, maxDepth(child));
        }
        return node.isObject() ? 1 + childDepth : 0;
    }
}
