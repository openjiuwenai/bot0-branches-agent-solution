/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import com.openjiuwen.gateway.governance.GovernanceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 730 default {@link AgentRuntimeClient}: forwards synchronously to the runtime
 * standard A2A entry {@code POST <endpoint>/a2a} (FEAT-001 / FEAT-011 §4.10
 * I-03). The body is forwarded opaquely (runtime JSON-RPC errors, e.g.
 * association -32001/-32004, are passed through as-is). Only a transport failure
 * (cannot reach the runtime) maps to a forward failure — never a fabricated
 * success.
 *
 * @since 0.1.0
 */
@Component
public class HttpAgentRuntimeClient implements AgentRuntimeClient {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentRuntimeClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public String invokeSync(String endpointUrl, String jsonRpcBody) {
        String url = (endpointUrl.endsWith("/") ? endpointUrl : endpointUrl + "/") + "a2a";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // Pass the body through (A2A errors arrive as 200 + JSON-RPC error; we don't fake success).
            return resp.body();
        } catch (IOException | InterruptedException ex) {
            // G.CON.10 forbids Thread.interrupt(); map transport failure to FORWARD_FAILED.
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Cannot reach runtime", ex);
        }
    }

    @Override
    public Stream<String> openStream(String endpointUrl, String jsonRpcBody) {
        String url = (endpointUrl.endsWith("/") ? endpointUrl : endpointUrl + "/") + "a2a";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            log.info("openStream status={}", resp.statusCode());
            requireStreamOk(resp, "stream subscription");
            return resp.body()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip())
                    .filter(data -> !data.isEmpty());
        } catch (IOException | InterruptedException ex) {
            // G.CON.10 forbids Thread.interrupt(); map transport failure to FORWARD_FAILED.
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Cannot open runtime stream", ex);
        }
    }

    @Override
    public Stream<String> openStreamByRef(String endpointUrl, String streamRef, String taskId, String tenantId) {
        String url = (endpointUrl.endsWith("/") ? endpointUrl : endpointUrl + "/") + "a2a";
        String subscribeBody = "{\"jsonrpc\":\"2.0\",\"id\":\"" + UUID.randomUUID()
                + "\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"" + taskId
                + "\",\"tenant\":\"" + tenantId + "\"}}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-OpenJiuwen-Stream-Ref", streamRef)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(subscribeBody, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            log.info("openStreamByRef status={} streamRef={}", resp.statusCode(), streamRef);
            requireStreamOk(resp, "SubscribeToTask subscription");
            return resp.body()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip())
                    .filter(data -> !data.isEmpty());
        } catch (IOException | InterruptedException ex) {
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Cannot open runtime stream by ref", ex);
        }
    }

    /**
     * Guards a streaming response: a runtime that rejects the subscription with a 4xx/5xx must
     * surface as a {@code FORWARD_FAILED} governance error (carrying the status + first body line
     * for diagnosis), not be silently swallowed as an empty stream — which masks the real cause
     * and yields an empty SSE to the client. The body is only read in the error branch.
     *
     * @param resp the runtime streaming response
     * @param what what was being subscribed (for the error message)
     */
    private static void requireStreamOk(HttpResponse<Stream<String>> resp, String what) {
        int status = resp.statusCode();
        if (status >= 400) {
            String firstLine = resp.body().findFirst().orElse("");
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Runtime rejected " + what + ": HTTP " + status
                            + (firstLine.isBlank() ? "" : " " + firstLine));
        }
    }

    @Override
    public String getTask(String endpointUrl, String taskId, String tenantId, Integer historyLength) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + UUID.randomUUID()
                + "\",\"method\":\"GetTask\",\"params\":{\"id\":\"" + taskId
                + "\",\"tenant\":\"" + tenantId + "\""
                + (historyLength != null ? ",\"historyLength\":" + historyLength : "")
                + "}}";
        return invokeSync(endpointUrl, body);
    }
}
