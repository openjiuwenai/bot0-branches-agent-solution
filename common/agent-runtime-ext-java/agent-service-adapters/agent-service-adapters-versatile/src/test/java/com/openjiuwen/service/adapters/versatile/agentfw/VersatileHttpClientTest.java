/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Tests Versatile HTTP client request construction and streaming handling.
 *
 * @since 2026-06-30
 */
class VersatileHttpClientTest {
    private static final char[] TEST_KEYSTORE_PASSWORD = "changeit".toCharArray();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonRequestAndStreamsLines() throws Exception {
        List<String> received = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/run", exchange -> {
            received.add(exchange.getRequestMethod());
            received.add(exchange.getRequestHeaders().getFirst("x-user-id"));
            received.add(String.valueOf(exchange.getRequestHeaders().get("Content-type").size()));
            received.add(exchange.getRequestURI().getQuery());
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(exchange.getProtocol());
            byte[] body = ("data: {\"event\":\"message\"}\n\n"
                    + "data: {\"data\":{\"node_type\":\"End\"}}\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        VersatileProperties properties = new VersatileProperties();
        VersatileHttpClient client = new VersatileHttpClient(properties);
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of("x-user-id", "u-1", "Content-Type", "application/json"),
                Map.of("workspace_id", "w-1"),
                Map.of("query", "q")
        );
        List<String> lines = new ArrayList<>();

        client.postStream(request, lines::add);

        assertThat(lines).contains("data: {\"event\":\"message\"}", "data: {\"data\":{\"node_type\":\"End\"}}");
        assertThat(received).containsExactly(
                "POST",
                "u-1",
                "1",
                "workspace_id=w-1",
                "{\"query\":\"q\"}",
                "HTTP/1.1"
        );
    }

    @Test
    void insecureSkipVerifyDoesNotAffectHttpRequestHeaders() throws Exception {
        List<Integer> contentTypeSizes = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/run", exchange -> {
            List<String> values = exchange.getRequestHeaders().get("Content-type");
            contentTypeSizes.add(values != null ? values.size() : 0);
            byte[] body = "data: {\"data\":{\"node_type\":\"End\"}}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        VersatileProperties properties = new VersatileProperties();
        properties.setInsecureSkipVerify(true);
        VersatileHttpClient client = new VersatileHttpClient(properties);
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of(),
                Map.of(),
                Map.of()
        );

        client.postStream(request, line -> { });

        assertThat(contentTypeSizes).containsExactly(0);
    }

    @Test
    void throwsOnHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/run", exchange -> {
            byte[] body = "bad".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        VersatileHttpClient client = new VersatileHttpClient(new VersatileProperties());
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of(),
                Map.of(),
                Map.of()
        );

        assertThatThrownBy(() -> client.postStream(request, line -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void rejectsUntrustedHttpsCertificateByDefault() throws Exception {
        server = createSelfSignedHttpsServer();
        server.start();

        VersatileHttpClient client = new VersatileHttpClient(new VersatileProperties());
        VersatileRequestExtractor.RemoteRequest request = selfSignedHttpsRequest();

        assertThatThrownBy(() -> client.postStream(request, line -> { }))
                .isInstanceOf(IOException.class);
    }

    @Test
    void insecureTlsAcceptsUntrustedCertificateAndHostnameMismatch() throws Exception {
        List<String> received = new ArrayList<>();
        server = createSelfSignedHttpsServer();
        server.createContext("/run", exchange -> {
            received.add(exchange.getRequestMethod());
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "data: {\"data\":{\"node_type\":\"End\"}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        VersatileProperties properties = new VersatileProperties();
        properties.setInsecureSkipVerify(true);
        VersatileHttpClient client = new VersatileHttpClient(properties);
        List<String> lines = new ArrayList<>();

        client.postStream(selfSignedHttpsRequest(), lines::add);

        assertThat(received).containsExactly("POST", "{\"query\":\"q\"}");
        assertThat(lines).containsExactly("data: {\"data\":{\"node_type\":\"End\"}}");
    }

    @Test
    void insecureTlsDoesNotFollowRedirects() throws Exception {
        AtomicBoolean redirected = new AtomicBoolean(false);
        server = createSelfSignedHttpsServer();
        server.createContext("/run", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirected.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        VersatileProperties properties = new VersatileProperties();
        properties.setInsecureSkipVerify(true);
        VersatileHttpClient client = new VersatileHttpClient(properties);

        assertThatThrownBy(() -> client.postStream(selfSignedHttpsRequest(), line -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("302");
        assertThat(redirected).isFalse();
    }

    @Test
    void masksRequestValuesForLogs() {
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "https://example.test/run",
                Map.of("Authorization", "Bearer token", "x-user-id", "u-1"),
                Map.of("api_key", "query-secret", "workspace_id", "w-1"),
                Map.of("custom_data", Map.of("password", "pwd", "query", "q"))
        );

        Map<String, Object> logRequest = VersatileHttpClient.logRequest(
                request, "https://example.test/run", true);

        assertThat(logRequest).containsEntry("url", "https://example.test/run");
        assertThat(logRequest.get("headers")).isEqualTo(
                Map.of("Authorization", "***masked***", "x-user-id", "***masked***"));
        assertThat(logRequest.get("params")).isEqualTo(
                Map.of("api_key", "***masked***", "workspace_id", "***masked***"));
        assertThat(logRequest.get("body")).isEqualTo(Map.of("custom_data", "***masked***"));
        assertThat(request.headers()).containsEntry("Authorization", "Bearer token");
        assertThat(request.params()).containsEntry("api_key", "query-secret");
        assertThat(request.body()).containsKey("custom_data");
    }

    @Test
    void buildsFullRequestForLogsWhenMaskingDisabled() {
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "https://example.test/run",
                Map.of("Authorization", "Bearer token", "x-user-id", "u-1"),
                Map.of("api_key", "query-secret", "workspace_id", "w-1"),
                Map.of("custom_data", Map.of("password", "pwd", "query", "q"))
        );

        Map<String, Object> logRequest = VersatileHttpClient.logRequest(
                request, "https://example.test/run?api_key=x", false);

        assertThat(logRequest).containsEntry("url", "https://example.test/run?api_key=x");
        assertThat(logRequest.get("headers")).isEqualTo(
                Map.of("Authorization", "Bearer token", "x-user-id", "u-1"));
        assertThat(logRequest.get("params")).isEqualTo(
                Map.of("api_key", "query-secret", "workspace_id", "w-1"));
        assertThat(logRequest.get("body")).isEqualTo(
                Map.of("custom_data", Map.of("password", "pwd", "query", "q")));
    }

    @Test
    void consumesResponseLinesBeforeResponseCompletes() throws Exception {
        CountDownLatch firstLineReceived = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        AtomicBoolean serverSawFirstLine = new AtomicBoolean(false);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/run", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {\"event\":\"message\"}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                serverSawFirstLine.set(firstLineReceived.await(2, TimeUnit.SECONDS));
                allowCompletion.countDown();
                exchange.getResponseBody().write(
                        "data: {\"data\":{\"node_type\":\"End\"}}\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                throw new IOException(exception);
            } finally {
                exchange.close();
            }
        });
        server.start();

        VersatileHttpClient client = new VersatileHttpClient(new VersatileProperties());
        VersatileRequestExtractor.RemoteRequest request = new VersatileRequestExtractor.RemoteRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of(),
                Map.of(),
                Map.of()
        );
        List<String> lines = new ArrayList<>();

        client.postStream(request, line -> {
            lines.add(line);
            if (line.contains("\"message\"")) {
                firstLineReceived.countDown();
                try {
                    assertThat(allowCompletion.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new IOException(exception);
                }
            }
        });

        assertThat(serverSawFirstLine).isTrue();
        assertThat(lines).containsExactly(
                "data: {\"event\":\"message\"}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        );
    }

    private VersatileRequestExtractor.RemoteRequest selfSignedHttpsRequest() {
        return new VersatileRequestExtractor.RemoteRequest(
                "https://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of("Content-Type", "application/json"),
                Map.of(),
                Map.of("query", "q")
        );
    }

    private static HttpsServer createSelfSignedHttpsServer() throws Exception {
        String encodedKeyStore;
        try (var stream = Objects.requireNonNull(
                VersatileHttpClientTest.class.getResourceAsStream("/tls/self-signed-server.p12.b64"))) {
            encodedKeyStore = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(Base64.getDecoder().decode(encodedKeyStore)),
                TEST_KEYSTORE_PASSWORD);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, TEST_KEYSTORE_PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return httpsServer;
    }
}
