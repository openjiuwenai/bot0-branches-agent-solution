/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command-line client that exercises the Versatile A2A adapter demo endpoint.
 *
 * @since 2026-06-30
 */
public final class VersatileA2AClientMain {
    private static final Logger log = LoggerFactory.getLogger(VersatileA2AClientMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private VersatileA2AClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String endpointUrl = System.getenv().getOrDefault("A2A_ENDPOINT_URL", "http://127.0.0.1:18080/a2a/");
        sendRequest(endpointUrl, requestJson("request-1.json"));
        sendRequest(endpointUrl, requestJson("request-2.json"));
        sendRequest(endpointUrl, requestJson("request-3.json"));
    }

    private static String requestJson(String fileName) throws Exception {
        String resourceName = "a2a-requests/" + fileName;
        try (InputStream inputStream = VersatileA2AClientMain.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing request resource: " + resourceName);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendRequest(String endpointUrl, String requestJson) throws Exception {
        Map<String, Object> root = MAPPER.readValue(requestJson, MAP_TYPE);
        Map<String, Object> params = mapValue(root.get("params"));
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        Map<String, Object> headers = new LinkedHashMap<>(mapValue(metadata.get("headers")));

        log.info("POST {}", endpointUrl);
        log.info("Request body:");
        log.info("{}", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(600))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");

        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getValue() != null) {
                requestBuilder.header(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        log.info("HTTP {}", response.statusCode());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("{}", line);
            }
        }
        log.info("");
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
