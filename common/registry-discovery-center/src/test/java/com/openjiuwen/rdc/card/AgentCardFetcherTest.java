/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

/**
 * AgentCardFetcherTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class AgentCardFetcherTest {
    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void fetch_valid_card_succeeds() {
            server.enqueue(new MockResponse()
            .setBody(validCard())
            .setHeader("Content-Type", "application/json"));

            AgentCardFetcher fetcher = new AgentCardFetcher();
            String baseUrl = server.url("/").toString().replaceAll("/$", "");
            AgentCardFetcher.FetchResult result = fetcher.fetchValidated(
            URI.create(baseUrl), "/.well-known/agent-card.json", Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.capabilityVersion()).isEqualTo("1.0.0");
        }

    @Test
    void fetch_http_error_returns_failure_code() {
        server.enqueue(new MockResponse().setResponseCode(503));

        AgentCardFetcher fetcher = new AgentCardFetcher();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        AgentCardFetcher.FetchResult result = fetcher.fetch(
                URI.create(baseUrl), "/.well-known/agent-card.json", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("AGENT_CARD_FETCH_FAILED");
    }

    @Test
    void fetch_rejects_unsupported_scheme() {
        AgentCardFetcher fetcher = new AgentCardFetcher();
        AgentCardFetcher.FetchResult result = fetcher.fetch(
                URI.create("ftp://example.com"), "/.well-known/agent-card.json", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("AGENT_CARD_SOURCE_REJECTED");
    }

    @Test
    void fetch_rejects_host_outside_allowed_cidr() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("10.0.0.0/8");

        AgentCardFetcher fetcher = AgentCardFetcher.fromSecurity(props);
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        AgentCardFetcher.FetchResult result = fetcher.fetch(
                URI.create(baseUrl), "/.well-known/agent-card.json", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("AGENT_CARD_SOURCE_REJECTED");
        assertThat(result.message()).contains("allowed CIDR");
    }

    @Test
    void fetch_with_signature_verification_rejects_unsigned_card() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.getSignerPemsByKid().put("test-key", minimalPublicKeyPem());

        server.enqueue(new MockResponse()
                .setBody(validCard())
                .setHeader("Content-Type", "application/json"));

        AgentCardFetcher fetcher = AgentCardFetcher.fromSecurity(props);
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        AgentCardFetcher.FetchResult result = fetcher.fetchValidated(
                java.net.URI.create(baseUrl), "/.well-known/agent-card.json", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("AGENT_CARD_SIGNATURE_INVALID");
    }

    private static String minimalPublicKeyPem() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair keyPair = generator.generateKeyPair();
            String encoded = java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String validCard() {
        return """
                {
                  "name": "demo",
                  "description": "demo",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                }
                """;
    }
}
