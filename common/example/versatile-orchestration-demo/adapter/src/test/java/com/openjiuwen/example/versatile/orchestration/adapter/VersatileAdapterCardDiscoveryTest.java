/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * VersatileAdapterCardDiscoveryTest
 *
 * @since 2026-07-08
 */
class VersatileAdapterCardDiscoveryTest {
    @Test
    void agentCardIsPublishedWithVersatileAdapterSkill() throws Exception {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(VersatileAdapterApplication.class)
                            .run("--server.port=0")) {
            int port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/agent-card.json"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).isEqualTo(200);
            String body = resp.body();
            assertThat(body).contains("versatile-adapter");
            assertThat(body).contains("versatile-bank-proxy");
        }
    }
}
