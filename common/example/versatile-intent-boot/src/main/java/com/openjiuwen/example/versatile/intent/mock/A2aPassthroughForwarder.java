/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Forwards an inbound A2A JSON-RPC request verbatim to a target runtime's
 * {@code /a2a/} endpoint and streams the response back, preserving task states
 * (e.g. {@code TASK_STATE_INPUT_REQUIRED}) and artifacts. Used by
 * {@link MockA2AGatewayController} for terminal business cards backed by a
 * real A2A agent (e.g. agent-b-deepagent-runtime), when the card is listed in
 * {@code openjiuwen.example.mock-a2a-gateway.passthrough-cards}.
 *
 * @since 0.1.0
 */
class A2aPassthroughForwarder {
    private static final String A2A_PATH = "/a2a/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    void forward(String targetBase, String agentId, String body, HttpServletResponse response)
            throws IOException {
        String url = targetBase.replaceAll("/+$", "") + A2A_PATH;
        String forwardBody = (body == null || body.isBlank()) ? "{}" : body;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(forwardBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(resp.statusCode());
            resp.headers().firstValue("Content-Type")
                    .ifPresent(response::setContentType);
            response.getOutputStream().write(resp.body());
            response.getOutputStream().flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("A2A passthrough interrupted", e);
        }
    }
}
