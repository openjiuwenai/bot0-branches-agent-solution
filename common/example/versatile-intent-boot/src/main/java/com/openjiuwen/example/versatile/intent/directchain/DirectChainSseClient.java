/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 通用 SSE POST 客户端：POST JSON body，逐行读取响应（text/event-stream），
 * 对每个非空行（含 "data:" 前缀）回调 LineConsumer。供直链 handler 与原始透传 handler 共用。
 */
final class DirectChainSseClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @FunctionalInterface
    interface LineConsumer {
        /**
         * 处理一行 SSE 响应。
         *
         * @param line 非空的 SSE 行（含 "data:" 前缀）
         * @throws IOException 读取或处理失败
         * @throws InterruptedException 线程被中断
         */
        void accept(String line) throws IOException, InterruptedException;
    }

    void postStream(String url, Map<String, Object> body, Map<String, String> headers,
            Duration timeout, LineConsumer consumer) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<java.io.InputStream> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Direct-chain HTTP " + response.statusCode());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                consumer.accept(line);
            }
        }
    }
}