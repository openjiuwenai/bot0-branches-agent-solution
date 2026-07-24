/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.openjiuwen.OpenJiuwenSkillHubProvider;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubException;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Unit tests for {@link OpenJiuwenSkillHubProvider} error-category preservation
 * (issue #29). Uses a JDK {@link HttpServer} as a mock Skill Hub so we can
 * assert that 401/403/404 responses surface as the original
 * {@link SkillHubErrorCategory} instead of being overwritten by the call-site
 * catch blocks.
 *
 * @since 2026-07-24
 */
class OpenJiuwenSkillHubProviderCategoryTest {
    private HttpServer server;
    private OpenJiuwenSkillHubProvider provider;
    private SkillHubConfig config;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new OpenJiuwenSkillHubProvider(endpoint, "dummy-token", "bearer");
        config = new SkillHubConfig();
        config.setEnabled(true);
        config.setEndpoint(endpoint);
        config.setAuthType("bearer");
        config.setEncryptedToken("");
        config.setLocalDir(tempDir.toString());
        provider.start(config, "dummy-token");
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.stop();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Issue #29: when Skill Hub returns 401 on the plugins list endpoint,
     * download() must throw {@link SkillHubException} with category
     * {@link SkillHubErrorCategory#AUTH_FAILED} — NOT the historical
     * {@code CONNECT_FAILED} that the listAllPublicSkills catch block used to
     * overwrite it with.
     */
    @Test
    void listSkills401SurfacesAsAuthFailed_Issue29() {
        mountStatus("/api/v1/plugins", 401, "application/json",
                "{\"detail\":{\"code\":401,\"message\":\"unauthorized\"}}");
        assertThatThrownBy(() -> provider.download(config, "dummy-token"))
                .isInstanceOf(SkillHubException.class)
                .hasMessageContaining("SkillHub[AUTH_FAILED]");
    }

    /**
     * Issue #29: 403 on plugins list must surface as AUTH_FAILED (the SPI does
     * not yet distinguish ACCESS_DENIED from AUTH_FAILED at HTTP layer; both
     * 401/403 map to AUTH_FAILED in sendJson).
     */
    @Test
    void listSkills403SurfacesAsAuthFailed_Issue29() {
        mountStatus("/api/v1/plugins", 403, "application/json",
                "{\"detail\":{\"code\":403,\"message\":\"forbidden\"}}");
        assertThatThrownBy(() -> provider.download(config, "dummy-token"))
                .isInstanceOf(SkillHubException.class)
                .hasMessageContaining("SkillHub[AUTH_FAILED]");
    }

    /**
     * Issue #29: 404 on the artifacts endpoint (required skill not found) must
     * surface as NOT_FOUND — NOT be overwritten by the fetchArtifactInfo catch
     * block. Because the list returns an empty array, download() succeeds with
     * no skills; to exercise fetchArtifactInfo we need a list response with at
     * least one skill so the download flow reaches artifact lookup. This test
     * is intentionally minimal — it asserts the list-level 404 case.
     */
    @Test
    void listSkills404SurfacesAsNotFound_Issue29() {
        mountStatus("/api/v1/plugins", 404, "application/json",
                "{\"detail\":{\"code\":404,\"message\":\"not found\"}}");
        assertThatThrownBy(() -> provider.download(config, "dummy-token"))
                .isInstanceOf(SkillHubException.class)
                .hasMessageContaining("SkillHub[NOT_FOUND]");
    }

    private void mountStatus(String path, int status, String contentType, String body) {
        HttpHandler handler = exchange -> {
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, payload.length);
            try (var os = exchange.getResponseBody()) {
                os.write(payload);
            }
        };
        server.createContext(path, handler);
    }
}
