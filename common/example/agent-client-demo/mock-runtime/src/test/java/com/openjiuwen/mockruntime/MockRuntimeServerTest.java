/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.mockruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;

/**
 * MockRuntimeServer 单元测试。
 */
class MockRuntimeServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws Exception {
        server = new MockRuntimeServer(0).startServer();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a2a");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getTaskReturnsDirectTaskResult() throws Exception {
        JsonNode accepted = post("""
                {"jsonrpc":"2.0","id":"send","method":"SendMessage","params":{
                  "message":{"contextId":"ctx","parts":[{"text":"hello"}]},
                  "configuration":{"returnImmediately":true},
                  "metadata":{"attributes":{"scenario":"async-gettask"}}
                }}
                """);
        String taskId = accepted.path("result").path("task").path("id").asText();

        JsonNode queried = post("""
                {"jsonrpc":"2.0","id":"get","method":"GetTask","params":{"id":"%s"}}
                """.formatted(taskId));

        assertEquals(taskId, queried.path("result").path("id").asText());
        assertFalse(queried.path("result").has("task"));
        assertTrue(queried.path("result").path("artifacts").isArray());
    }

    @Test
    void subscribeStartsWithSnapshotUsesRuntimeSseFormat() throws Exception {
        String stream = postText("""
                {"jsonrpc":"2.0","id":"stream","method":"SendStreamingMessage","params":{
                  "message":{"contextId":"ctx","parts":[{"text":"hello"}]},
                  "metadata":{"attributes":{"scenario":"streaming-resubscribe"}}
                }}
                """);
        String taskId = dataFrames(stream).get(0).path("result").path("artifactUpdate").path("taskId").asText();

        String subscribed = postText("""
                {"jsonrpc":"2.0","id":"subscribe","method":"SubscribeToTask","params":{"id":"%s"}}
                """.formatted(taskId));
        List<JsonNode> frames = dataFrames(subscribed);

        assertFalse(subscribed.contains("id:"));
        assertTrue(subscribed.startsWith("event:jsonrpc\n"));
        assertEquals(taskId, frames.get(0).path("result").path("id").asText());
        assertEquals("TASK_STATE_WORKING",
                frames.get(0).path("result").path("status").path("state").asText());
        assertEquals("root-output",
                frames.get(0).path("result").path("artifacts").get(0).path("artifactId").asText());
        assertTrue(frames.stream().skip(1).anyMatch(frame ->
                "TASK_STATE_COMPLETED".equals(frame.path("result").path("statusUpdate")
                        .path("status").path("state").asText())));

        JsonNode terminalSubscribe = post("""
                {"jsonrpc":"2.0","id":"again","method":"SubscribeToTask","params":{"id":"%s"}}
                """.formatted(taskId));
        assertEquals(-32004, terminalSubscribe.path("error").path("code").asInt());
    }

    @Test
    void subscribeRejectsLegacyOrMissingTaskIdParameter() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":"bad","method":"SubscribeToTask","params":{"taskId":"legacy"}}
                """);

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void adminScenarioSelectsScriptWithoutA2aAttributes() throws Exception {
        URI admin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/admin/scenario");
        HttpRequest configure = HttpRequest.newBuilder(admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"contextId\":\"ctx-control\",\"scenario\":\"nested-5\"}"))
                .build();
        assertEquals(200, HttpClient.newHttpClient().send(configure,
                HttpResponse.BodyHandlers.ofString()).statusCode());

        String stream = postText("""
                {"jsonrpc":"2.0","id":"stream","method":"SendStreamingMessage","params":{
                  "message":{"contextId":"ctx-control","parts":[{"text":"hello"}]},
                  "metadata":{}
                }}
                """);

        long delegations = dataFrames(stream).stream()
                .filter(frame -> "delegation".equals(frame.path("result").path("artifactUpdate")
                        .path("artifact").path("metadata").path("agentEvent").path("type").asText()))
                .count();
        assertEquals(5, delegations);
    }

    @Test
    void rootOutputReplaceScenarioUsesSameArtifactIdWithReplaceUpdates() throws Exception {
        URI admin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/admin/scenario");
        HttpRequest configure = HttpRequest.newBuilder(admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"contextId\":\"ctx-replace\",\"scenario\":\"root-output-replace\"}"))
                .build();
        assertEquals(200, HttpClient.newHttpClient().send(configure,
                HttpResponse.BodyHandlers.ofString()).statusCode());

        String stream = postText("""
                {"jsonrpc":"2.0","id":"stream","method":"SendStreamingMessage","params":{
                  "message":{"contextId":"ctx-replace","parts":[{"text":"hello"}]},
                  "metadata":{}
                }}
                """);
        List<JsonNode> updates = dataFrames(stream).stream()
                .map(frame -> frame.path("result").path("artifactUpdate"))
                .filter(JsonNode::isObject)
                .toList();

        assertEquals(2, updates.size());
        assertTrue(updates.stream().allMatch(update -> !update.path("append").asBoolean(true)));
        assertTrue(updates.stream().allMatch(update ->
                "root-output".equals(update.path("artifact").path("artifactId").asText())));
        assertEquals("OLD_ROOT_TEXT", updates.get(0).path("artifact").path("parts").get(0)
                .path("text").asText());
        assertEquals("NEW_ROOT_TEXT", updates.get(1).path("artifact").path("parts").get(0)
                .path("text").asText());
    }

    private JsonNode post(String body) throws Exception {
        return JSON.readTree(postText(body));
    }

    private String postText(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static List<JsonNode> dataFrames(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> {
                    try {
                        return JSON.readTree(line.substring("data:".length()));
                    } catch (JsonProcessingException error) {
                        try {
                            return JSON.readTree(line.substring("data:".length()));
                        } catch (IOException e) {
                            throw new IllegalArgumentException(e);
                        }
                    }
                })
                .toList();
    }
}
