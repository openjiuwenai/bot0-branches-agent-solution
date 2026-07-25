/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.example.versatile.orchestration.planagent.PlanAgentApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Boots the real {@link PlanAgentApplication} and proves the custom-rest endpoint is mounted
 * and the gateway adapter bean is wired, without contacting the versatile backend.
 *
 * <p>Strategy: {@code stream:true} + {@code Accept: application/json} (not event-stream) makes the
 * framework reject with {@code 406 stream_not_acceptable} BEFORE execution, so the agent handler is
 * never invoked. The 406 is projected through {@code fromError} as a gateway error envelope.
 *
 * @since 0.2.0
 */
class GatewayEndpointWiringTest {
    @Test
    void customEndpointMountedAndProjectsPreExecutionErrorAsEnvelope() throws Exception {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(PlanAgentApplication.class).run("--server.port=0")) {
            int port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
            String body = "{\"stream\":true,\"conversation_id\":\"conv-1\"}";
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/v1/proj-1/agents/agent-1/conversations/conv-1"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).isEqualTo(406);
            assertThat(resp.body()).contains("\"success\":false");
            assertThat(resp.body()).contains("\"conversation_id\":\"conv-1\"");
            assertThat(resp.body()).contains("\"event\":\"error\"");
        }
    }
}
