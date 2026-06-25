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

public class VersatileHttpClient {

    private static final Logger log = LoggerFactory.getLogger(VersatileHttpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VersatileProperties properties;
    private final HttpClient httpClient;

    public VersatileHttpClient(VersatileProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().build();
    }

    public void postStream(VersatileRequestExtractor.RemoteRequest request, LineConsumer consumer)
            throws IOException, InterruptedException {
        String body = OBJECT_MAPPER.writeValueAsString(request.body());
        String url = withQueryParams(request.url(), request.params());
        log.info("Posting Versatile request url={} intent={} headers={} params={} body_keys={}",
                url, request.intent(), request.headers().size(), request.params().size(), request.body().keySet());
        log.debug("Versatile outbound request={}", logRequest(request, url));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        builder.setHeader("Content-Type", "application/json");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        log.info("Received Versatile response status={} url={}", response.statusCode(), url);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody = readAll(response.body());
            log.warn("Versatile HTTP error status={} body={}", response.statusCode(), responseBody);
            throw new VersatileHttpException(response.statusCode(), responseBody);
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
        data.put("intent", request.intent());
        data.put("headers", request.headers());
        data.put("params", request.params());
        data.put("body", request.body());
        return data;
    }

    private String readAll(InputStream body) throws IOException {
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Duration timeout() {
        return properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(600);
    }

    private String withQueryParams(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                joiner.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
            }
        }
        if (joiner.length() == 0) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + joiner;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    public interface LineConsumer {
        void accept(String line) throws IOException, InterruptedException;
    }

    public static class VersatileHttpException extends IOException {
        private final int statusCode;
        private final String responseBody;

        public VersatileHttpException(int statusCode, String responseBody) {
            super("Versatile HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
