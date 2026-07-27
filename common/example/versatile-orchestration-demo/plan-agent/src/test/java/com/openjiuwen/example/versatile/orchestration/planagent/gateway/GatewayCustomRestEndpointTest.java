/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * End-to-end: the gateway endpoint streams a deterministic set of gateway envelopes as byte-exact
 * SSE {@code data:<json>\n\n} frames. A stub {@link AgentHandler} feeds scripted chunks so no real
 * backend is required.
 *
 * @since 0.2.0
 */
class GatewayCustomRestEndpointTest {
    // createdTime is supplied in the payload so the frame is deterministic (clock not exercised).
    private static final String LLM_REASONING =
            "{\"type\":\"llm_reasoning\",\"index\":2,\"payload\":{\"result_type\":\"answer\","
                    + "\"content\":\"用户\",\"createdTime\":1719000000000}}";
    private static final String LLM_USAGE =
            "{\"type\":\"llm_usage\",\"index\":3,\"payload\":{\"result_type\":\"answer\"}}";

    private static final String EXPECTED_THINK_FRAME =
            "data:{\"success\":true,\"agent_id\":\"agent-1\",\"conversation_id\":\"conv-1\","
                    + "\"output\":\"\",\"error\":\"\",\"execution_time\":\"\","
                    + "\"custom_rsp_data\":{\"event\":\"think_chunk\",\"content\":\"用户\","
                    + "\"createdTime\":1719000000000,\"latency\":\"\",\"plugin\":\"\",\"data\":{}}}\n\n";

    @Test
    void streamsGatewayEnvelopesAsByteExactSseFrames() throws Exception {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(TestApp.class).run("--server.port=0")) {
            int port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
            String body = "{\"role_name\":\"MobileClient\","
                    + "\"input\":{\"query\":\"查尾号为4241的卡的余额\"},"
                    + "\"agent_id\":\"agent-1\",\"stream\":true,\"conversation_id\":\"conv-1\","
                    + "\"custom_data\":{\"inputs\":{\"query\":\"查尾号为4241的卡的余额\"}}}";

            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/v1/proj-1/agents/agent-1/conversations/conv-1"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");

            String responseBody = resp.body();
            // The llm_reasoning chunk is projected as an exact think_chunk frame, raw UTF-8.
            assertThat(responseBody).contains(EXPECTED_THINK_FRAME);
            assertThat(responseBody).contains("用户");

            // Every frame is a byte-exact data:<json> line; split on the SSE record terminator.
            List<String> frames = Arrays.stream(responseBody.split("\n\n", -1))
                    .filter(s -> !s.isEmpty())
                    .toList();
            assertThat(frames).isNotEmpty();
            assertThat(frames).allMatch(f -> f.startsWith("data:"));

            // Exactly one think_chunk; every other frame's custom_rsp_data is an empty object
            // (the llm_usage artifact + WORKING/COMPLETED status events are all ignorable).
            ObjectMapper mapper = new ObjectMapper();
            int thinkCount = 0;
            for (String frame : frames) {
                JsonNode env = mapper.readTree(frame.substring("data:".length()));
                JsonNode custom = env.get("custom_rsp_data");
                assertThat(custom.isObject()).isTrue();
                if (custom.has("event") && "think_chunk".equals(custom.get("event").asText())) {
                    assertThat(custom.get("content").asText()).isEqualTo("用户");
                    assertThat(custom.get("createdTime").asLong()).isEqualTo(1719000000000L);
                    thinkCount++;
                } else {
                    assertThat(custom.size()).isZero();
                }
            }
            assertThat(thinkCount).isEqualTo(1);
        }
    }

    /**
     * Minimal app: applies the framework autoconfiguration but provides only a stub handler.
     * <p>
     * Separate from {@code PlanAgentApplication} so its real {@code planAgentHandler}
     * bean (which drives the ReActAgent planner) is never registered. This class MUST live at
     * {@code ...planagent.gateway} or deeper: {@code @SpringBootApplication} scans from the annotated
     * class's package downward, so staying below the parent {@code ...planagent} package keeps the
     * scan root from reaching {@code PlanAgentApplication}. Do not move it up the package
     * tree or it will silently pick up the real handler and shadow the stub.
     */
    @SpringBootApplication
    static class TestApp {
        @Bean
        CustomRestProtocolAdapter gatewayProtocolAdapter(ObjectMapper objectMapper) {
            return new GatewayProtocolAdapter(objectMapper);
        }

        @Bean
        AgentHandler stubAgentHandler() {
            return new AgentHandler() {
                @Override
                public QueryResponse query(ServeRequest request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, LLM_REASONING));
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, LLM_USAGE));
                    observer.onComplete();
                }
            };
        }
    }
}
