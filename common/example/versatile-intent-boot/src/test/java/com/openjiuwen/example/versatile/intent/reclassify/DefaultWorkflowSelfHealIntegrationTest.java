/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration test: L2 with configured {@code default-workflow.agent-card}
 * emits an {@code a2a_delegate} interrupt pointing at the default workflow
 * when {@code intent_id="1"}.
 */
class DefaultWorkflowSelfHealIntegrationTest {
    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void emitsA2aDelegateToDefaultWorkflowOnAmbiguousIntent() {
        wireMock.stubFor(post(urlPathMatching("/v1/.*/agents/agent_L2/.*"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                                + "\"data\":{\"node_type\":\"QA\","
                                + "\"response_content\":\"无法确定\","
                                + "\"intent_id\":\"1\",\"agent_id\":\"\"}}}\n\n"
                                + "data: {\"data\":{\"node_type\":\"End\"}}\n\n")));

        VersatileProperties props = new VersatileProperties();
        // The extractor only substitutes {conversation_id}; {project_id} would
        // remain literal and trip URI.create. Use a concrete path segment so
        // the URL stays valid while still matching the stub's regex.
        props.setUrlTemplate("http://localhost:" + wireMock.port()
                + "/v1/proj/agents/agent_L2/conversations/{conversation_id}");
        props.setResultNodeName("AnswerNode");
        VersatileProperties.ResultExtraction re1 = new VersatileProperties.ResultExtraction();
        re1.setMatch("response_content");
        re1.setGet("/custom_rsp_data/data/response_content");
        VersatileProperties.ResultExtraction re2 = new VersatileProperties.ResultExtraction();
        re2.setMatch("intent_id");
        re2.setGet("/custom_rsp_data/data/intent_id");
        VersatileProperties.ResultExtraction re3 = new VersatileProperties.ResultExtraction();
        re3.setMatch("agent_id");
        re3.setGet("/custom_rsp_data/data/agent_id");
        props.setResultExtractions(List.of(re1, re2, re3));
        props.getDefaultWorkflow().setAgentCard("agent_card_L2_default");

        VersatileAgentHandler handler = new VersatileAgentHandler(props);
        ServeRequest request = new ServeRequest();
        request.setConversationId("conv-1");
        request.setStream(false);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "订一张北京到上海的机票");
        messages.add(userMsg);
        request.setMessages(messages);

        QueryResponse response = handler.query(request);
        Object result = response.getResult();
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        Object interrupt = resultMap.get("_interrupt");
        assertThat(interrupt).isInstanceOf(Map.class);
        Map<?, ?> interruptMap = (Map<?, ?>) interrupt;
        assertThat(interruptMap.get("agentName")).isEqualTo("agent_card_L2_default");
        assertThat(interruptMap.get("resume")).isEqualTo(false);
        Map<?, ?> context = (Map<?, ?>) interruptMap.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
        assertThat(context.get("agentName")).isEqualTo("agent_card_L2_default");
    }
}
