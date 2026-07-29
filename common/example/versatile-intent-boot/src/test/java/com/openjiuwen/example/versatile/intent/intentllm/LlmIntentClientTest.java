package com.openjiuwen.example.versatile.intent.intentllm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class LlmIntentClientTest {
    private WireMockServer wm;
    private LlmIntentProperties props;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        props = new LlmIntentProperties();
        props.setBaseUrl(wm.baseUrl());
        props.setApiKey("k");
        props.setModel("m");
        props.setMaxRetries(0);
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    @Test
    void returnsContentChoices() {
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"content\":"
                                + "\"{\\\"action\\\":\\\"classify\\\"}\"}}]}")));
        LlmIntentClient client = new LlmIntentClient(props);
        assertThat(client.complete(List.of(Map.of("role", "user", "content", "x"))))
                .isEqualTo("{\"action\":\"classify\"}");
    }

    @Test
    void throwsOnHttpFailure() {
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        LlmIntentClient client = new LlmIntentClient(props);
        assertThatThrownBy(() -> client.complete(List.of(Map.of("role", "user", "content", "x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void sendsModelAndTemperature() {
        wm.stubFor(post(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("m")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.0")))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));
        LlmIntentClient client = new LlmIntentClient(props);
        client.complete(List.of(Map.of("role", "user", "content", "x")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }
}
