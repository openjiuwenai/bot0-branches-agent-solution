/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the config-gated A2A-native passthrough branch of
 * {@link MockA2AGatewayController}: cards listed in {@code passthroughCards}
 * forward verbatim to the target {@code /a2a/} endpoint (preserving native A2A
 * task states), while non-passthrough cards fall back to the legacy
 * {@code /v1/query} translation.
 *
 * @since 0.1.0
 */
class MockA2AGatewayPassthroughTest {
    private WireMockServer wm;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void passthroughCardForwardsToA2aEndpointVerbatim() throws Exception {
        String upstream = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":"
                + "{\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\"}}}}";
        wm.stubFor(post(urlPathEqualTo("/a2a/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(upstream)));
        wm.stubFor(post(urlPathEqualTo("/v1/query"))
                .willReturn(aResponse().withStatus(200).withBody("{\"should_not_be_hit\":true}")));

        MockA2AGatewayController controller = new MockA2AGatewayController(
                Map.of("agent_card_biz_hotel_domestic", wm.baseUrl()),
                Set.of("agent_card_biz_hotel_domestic"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        String body = "{\"conversation_id\":\"c-passthru\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"订酒店\"}]}";
        String resp = mockMvc.perform(MockMvcRequestBuilders.post("/a2a/agent_card_biz_hotel_domestic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(resp).contains("TASK_STATE_INPUT_REQUIRED");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/a2a/")));
        wm.verify(0, postRequestedFor(urlPathEqualTo("/v1/query")));
    }

    @Test
    void nonPassthroughCardFallsBackToV1QueryTranslation() throws Exception {
        // passthroughCards 为空 → 走原 /v1/query 翻译路径，不触达 /a2a/
        wm.stubFor(post(urlPathEqualTo("/a2a/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"should_not_be_hit\":true}")));
        wm.stubFor(post(urlPathEqualTo("/v1/query"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"custom_rsp_data\":{\"data\":{\"response_content\":\"ok\","
                                + "\"intent_id\":\"x\",\"agent_id\":\"x\"}}}")));

        MockA2AGatewayController controller = new MockA2AGatewayController(
                Map.of("agent_card_biz_hotel_domestic", wm.baseUrl()),
                Set.of());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        String body = "{\"conversation_id\":\"c-no-passthru\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"订酒店\"}]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/a2a/agent_card_biz_hotel_domestic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        wm.verify(0, postRequestedFor(urlPathEqualTo("/a2a/")));
        wm.verify(postRequestedFor(urlPathEqualTo("/v1/query")));
    }

    private static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
            postRequestedFor(com.github.tomakehurst.wiremock.matching.UrlPattern pattern) {
        return com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(pattern);
    }
}
