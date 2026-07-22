/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.openjiuwen.gateway.governance.GovernanceException;

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
        } catch (Exception ex) {
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Cannot reach runtime at " + endpointUrl, ex);
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
            return resp.body()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip())
                    .filter(data -> !data.isEmpty());
        } catch (Exception ex) {
            throw new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                    "Cannot open runtime stream at " + endpointUrl, ex);
        }
    }
}
