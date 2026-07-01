/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * HTTP client for invoking Versatile-compatible streaming endpoints.
 *
 * @since 2026-06-30
 */
final class VersatileHttpClient {
    private static final Logger log = LoggerFactory.getLogger(VersatileHttpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VersatileProperties properties;
    private final HttpClient httpClient;

    VersatileHttpClient(VersatileProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    void postStream(VersatileRequestExtractor.RemoteRequest request, LineConsumer consumer)
            throws IOException, InterruptedException {
        String body = OBJECT_MAPPER.writeValueAsString(request.body());
        String url = withQueryParams(request.url(), request.params());
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(600);
        log.info("Posting Versatile request url={} headers={} params={} body_keys={}",
                url, request.headers().size(), request.params().size(), request.body().keySet());
        log.debug("Versatile outbound request={}", logRequest(request, url));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));

        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        log.info("Received Versatile response status={} url={}", response.statusCode(), url);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody;
            try (InputStream responseBodyStream = response.body()) {
                responseBody = new String(responseBodyStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.warn("Versatile HTTP error status={} body={}", response.statusCode(), responseBody);
            throw new IOException("Versatile HTTP " + response.statusCode() + ": " + responseBody);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.debug("Versatile response line={}", line);
                    consumer.accept(line);
                }
            }
        }
    }

    static Map<String, Object> logRequest(VersatileRequestExtractor.RemoteRequest request, String url) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("headers", request.headers());
        data.put("params", request.params());
        data.put("body", request.body());
        return data;
    }

    private String withQueryParams(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        if (joiner.length() == 0) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + joiner;
    }

    /**
     * Consumes one non-blank response line.
     *
     * @since 2026-06-30
     */
    @FunctionalInterface
    interface LineConsumer {
        /**
         * Accepts one decoded response line.
         *
         * @param line response line
         * @throws IOException when line processing fails
         * @throws InterruptedException when line processing is interrupted
         */
        void accept(String line) throws IOException, InterruptedException;
    }
}
