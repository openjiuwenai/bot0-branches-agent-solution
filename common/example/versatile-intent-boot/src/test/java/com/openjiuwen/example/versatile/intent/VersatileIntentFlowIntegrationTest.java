/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.openjiuwen.example.versatile.intent.a2a.InProcessRemoteAgentCaller;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration test for the Versatile intent L1->L2 three-field
 * forwarding flow.
 *
 * <p>WireMock stubs the Versatile L1/L2 SSE endpoints; {@link InProcessRemoteAgentCaller}
 * forwards L1's three-field result to L2 without HTTP; the orchestrator's sync
 * {@code query()} path drives the whole chain. Verifies that the final
 * {@link QueryResponse#getResult()} carries L2's {@code response_content} and
 * {@code agent_id}.
 *
 * @since 0.1.0
 */
class VersatileIntentFlowIntegrationTest {
    private WireMockServer wireMock;
    private int wireMockPort;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMockPort = wireMock.port();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void l1ForwardsToL2WithResponseContentAppendedAsAssistantMessage() {
        // 1. Stub L1 Versatile SSE: returns three-field result pointing to L2.
        stubVersatileSse("agent_L1", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"response_content\":\"L1输出\","
                        + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));

        // 2. Stub L2 Versatile SSE: returns three-field result with downstream agent_id.
        stubVersatileSse("agent_L2", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"response_content\":\"L2输出\","
                        + "\"intent_id\":\"intent_L2_hotel_domestic\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));

        // 3. Build L1 handler (HTTP client goes to WireMock).
        VersatileProperties l1Props = buildL1Properties();
        VersatileAgentHandler l1Handler = new VersatileAgentHandler(l1Props);

        // 4. Build L2 handler (HTTP client goes to WireMock).
        VersatileProperties l2Props = buildL2Properties();
        VersatileAgentHandler l2Handler = new VersatileAgentHandler(l2Props);

        // 5. Wire InProcess caller: L1 -> L2.
        RemoteAgentCaller inProcess = new InProcessRemoteAgentCaller(
                Map.of("agent_card_L2_hotel", l2Handler));
        RemoteAgentCardResolver resolver = mock(RemoteAgentCardResolver.class);
        // ActiveStreamRegistry is only used by streamQuery; a real instance is safe for sync query().
        ActiveStreamRegistry streamRegistry = new ActiveStreamRegistry();

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                l1Handler, new InMemoryTaskStore(), inProcess, resolver, streamRegistry, "agent-L1");

        // 6. Send user request to L1 orchestrator.
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        QueryResponse response = orchestrator.query(request);

        // 7. Final response carries L2's response_content and agent_id.
        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("response_content")).isEqualTo("L2输出");
        assertThat(result.get("agent_id")).isEqualTo("agent_card_biz_hotel_domestic");
    }

    private void stubVersatileSse(String agentPathSegment, List<String> sseLines) {
        StringBuilder body = new StringBuilder();
        for (String line : sseLines) {
            body.append(line).append("\n\n");
        }
        wireMock.stubFor(WireMock.post(WireMock.urlPathMatching(".*/agents/" + agentPathSegment + "/conversations/.*"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body.toString())));
    }

    private VersatileProperties buildL1Properties() {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate("http://localhost:" + wireMockPort
                + "/v1/proj/agents/agent_L1/conversations/{conversation_id}");
        p.setResultNodeName("AnswerNode");
        VersatileProperties.Intent hotel = new VersatileProperties.Intent();
        hotel.setId("intent_L1_hotel");
        hotel.setName("酒店");
        p.setIntents(List.of(hotel));
        p.getMessages().setRequired(true);
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_L2_hotel");
        p.getIntentAgentMapping().put("intent_L1_hotel", List.of(candidate));
        addExtraction(p, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(p, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(p, "agent_id", "/custom_rsp_data/data/agent_id");
        return p;
    }

    private VersatileProperties buildL2Properties() {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate("http://localhost:" + wireMockPort
                + "/v1/proj/agents/agent_L2/conversations/{conversation_id}");
        p.setResultNodeName("AnswerNode");
        VersatileProperties.Intent hotelDom = new VersatileProperties.Intent();
        hotelDom.setId("intent_L2_hotel_domestic");
        hotelDom.setName("国内酒店");
        p.setIntents(List.of(hotelDom));
        p.getMessages().setRequired(true);
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_biz_hotel_domestic");
        p.getIntentAgentMapping().put("intent_L2_hotel_domestic", List.of(candidate));
        addExtraction(p, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(p, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(p, "agent_id", "/custom_rsp_data/data/agent_id");
        return p;
    }

    private static void addExtraction(VersatileProperties p, String match, String get) {
        VersatileProperties.ResultExtraction e = new VersatileProperties.ResultExtraction();
        e.setMatch(match);
        e.setGet(get);
        p.getResultExtractions().add(e);
    }
}
