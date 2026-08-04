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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP client for invoking Versatile-compatible streaming endpoints.
 *
 * @since 2026-06-30
 */
final class VersatileHttpClient {
    private static final Logger log = LoggerFactory.getLogger(VersatileHttpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MASKED_VALUE = "***masked***";
    private static final HostnameVerifier TRUST_ALL_HOSTNAMES = (hostname, session) -> true;

    private final VersatileProperties properties;
    private final HttpClient httpClient;
    private final SSLSocketFactory insecureSslSocketFactory;

    VersatileHttpClient(VersatileProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        if (properties.isInsecureSkipVerify()) {
            this.insecureSslSocketFactory = createInsecureSslSocketFactory();
            log.warn("Versatile TLS certificate and hostname verification is disabled");
        } else {
            this.insecureSslSocketFactory = null;
        }
    }

    void postStream(VersatileRequestExtractor.RemoteRequest request, LineConsumer consumer)
            throws IOException, InterruptedException {
        String body = OBJECT_MAPPER.writeValueAsString(request.body());
        String url = withQueryParams(request.url(), request.params());
        boolean maskSensitive = properties.isLogMaskSensitive();
        String logUrl = maskSensitive ? request.url() : url;
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(600);
        log.info("Posting Versatile request url={} headers={} params={} body_keys={}",
                logUrl, request.headers().size(), request.params().size(), request.body().keySet());
        log.debug("Versatile outbound request={}", logRequest(request, logUrl, maskSensitive));

        if (insecureSslSocketFactory != null && isHttps(url)) {
            postInsecureHttps(request, consumer, body, url, logUrl, timeout);
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));

        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }

        HttpResponse<InputStream> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        log.info("Received Versatile response status={} url={}", response.statusCode(), logUrl);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody;
            try (InputStream responseBodyStream = response.body()) {
                responseBody = new String(responseBodyStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.warn("Versatile HTTP error status={} body={}", response.statusCode(), responseBody);
            throw new IOException("Versatile HTTP " + response.statusCode() + ": " + responseBody);
        }

        consumeLines(response.body(), consumer);
    }

    private void postInsecureHttps(VersatileRequestExtractor.RemoteRequest request, LineConsumer consumer,
            String body, String url, String logUrl, Duration timeout) throws IOException, InterruptedException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setSSLSocketFactory(insecureSslSocketFactory);
        connection.setHostnameVerifier(TRUST_ALL_HOSTNAMES);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        int timeoutMillis = timeoutMillis(timeout);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bodyBytes.length);
        request.headers().forEach(connection::setRequestProperty);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }
            int statusCode = connection.getResponseCode();
            connection.setReadTimeout(0);
            log.info("Received Versatile response status={} url={}", statusCode, logUrl);
            if (statusCode < HttpURLConnection.HTTP_OK || statusCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                String responseBody;
                InputStream errorStream = connection.getErrorStream();
                if (errorStream == null) {
                    responseBody = "";
                } else {
                    try (errorStream) {
                        responseBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                log.warn("Versatile HTTP error status={} body={}", statusCode, responseBody);
                throw new IOException("Versatile HTTP " + statusCode + ": " + responseBody);
            }
            consumeLines(connection.getInputStream(), consumer);
        } finally {
            connection.disconnect();
        }
    }

    private static void consumeLines(InputStream inputStream, LineConsumer consumer)
            throws IOException, InterruptedException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.debug("Versatile response line={}", line);
                    consumer.accept(line);
                }
            }
        }
    }

    private static boolean isHttps(String url) {
        return "https".equalsIgnoreCase(URI.create(url).getScheme());
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("Versatile timeout must be positive");
        }
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private static SSLSocketFactory createInsecureSslSocketFactory() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Compatibility mode intentionally accepts every client certificate.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Compatibility mode intentionally accepts every server certificate.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to initialize insecure Versatile TLS context", exception);
        }
    }

    static Map<String, Object> logRequest(VersatileRequestExtractor.RemoteRequest request, String url) {
        return logRequest(request, url, false);
    }

    static Map<String, Object> logRequest(
            VersatileRequestExtractor.RemoteRequest request, String url, boolean maskSensitive) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("headers", maskSensitive ? maskValues(request.headers()) : request.headers());
        data.put("params", maskSensitive ? maskValues(request.params()) : request.params());
        data.put("body", maskSensitive ? maskValues(request.body()) : request.body());
        return data;
    }

    private static Map<String, Object> maskValues(Map<?, ?> source) {
        Map<String, Object> masked = new LinkedHashMap<>();
        source.forEach((key, value) -> masked.put(String.valueOf(key), MASKED_VALUE));
        return masked;
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
