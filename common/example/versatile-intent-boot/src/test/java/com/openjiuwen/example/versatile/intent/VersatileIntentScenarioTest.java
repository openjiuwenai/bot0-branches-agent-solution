/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.openjiuwen.example.versatile.intent.a2a.InProcessRemoteAgentCaller;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
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
 * Scenario tests mirroring L2 §6.2 user examples.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>§6.2.1 两层识别 + 下游业务 — L1→L2→downstream full chain via
 *       orchestrator-wrapping (each layer's InProcess caller forwards to the
 *       next layer's orchestrator, not just its handler, so three-field
 *       forwarding recurses across layers).</li>
 *   <li>§6.2.2 分类错误重新分类 — downstream returns three-field pointing back
 *       to L1; L1 is re-invoked and returns a new classification.</li>
 *   <li>§6.2.3 工作流显式用户交互 — L1 returns native interrupt; the final
 *       result carries {@code _interrupt={message,input_requirement,resume_token}}.</li>
 * </ol>
 *
 * <p>WireMock stubs each layer's Versatile SSE endpoint. The InProcess caller
 * wraps the next layer's orchestrator as an {@link AgentHandler} so
 * {@code streamQuery} recursion crosses layer boundaries without HTTP.
 *
 * @since 0.1.0
 */
class VersatileIntentScenarioTest {
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
    void scenarioOneTwoLayerRecognitionPlusDownstream() {
        stubSse("agent_L1", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"response_content\":\"L1酒店意图\","
                        + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));
        stubSse("agent_L2", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"response_content\":\"L2国内酒店\","
                        + "\"intent_id\":\"intent_L2_hotel_domestic\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));
        stubSse("agent_biz", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"text\":\"酒店预订成功：上海今晚五星\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));

        VersatileAgentHandler downstreamHandler = new VersatileAgentHandler(legacyProps("agent_biz"));
        A2AEnabledServeOrchestrator downstreamOrch = new A2AEnabledServeOrchestrator(
                downstreamHandler, new InMemoryTaskStore(), mock(RemoteAgentCaller.class),
                mock(RemoteAgentCardResolver.class), new ActiveStreamRegistry(), "agent-downstream");

        VersatileAgentHandler l2Handler = new VersatileAgentHandler(l2Props());
        RemoteAgentCaller l2Caller = new InProcessRemoteAgentCaller(
                Map.of("agent_card_biz_hotel_domestic", asHandler(downstreamOrch)));
        A2AEnabledServeOrchestrator l2Orch = new A2AEnabledServeOrchestrator(
                l2Handler, new InMemoryTaskStore(), l2Caller, mock(RemoteAgentCardResolver.class),
                new ActiveStreamRegistry(), "agent-L2");

        VersatileAgentHandler l1Handler = new VersatileAgentHandler(l1Props());
        RemoteAgentCaller l1Caller = new InProcessRemoteAgentCaller(
                Map.of("agent_card_L2_hotel", asHandler(l2Orch)));
        A2AEnabledServeOrchestrator l1Orch = new A2AEnabledServeOrchestrator(
                l1Handler, new InMemoryTaskStore(), l1Caller, mock(RemoteAgentCardResolver.class),
                new ActiveStreamRegistry(), "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.setStream(false);
        request.setUserId("u1");
        request.setTenantId("t1");
        request.setMessages(List.of(Map.of("role", "user", "content", "我要订酒店")));

        QueryResponse response = l1Orch.query(request);

        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("content")).isEqualTo("酒店预订成功：上海今晚五星");
        assertThat(result.get("response_content")).isEqualTo("酒店预订成功：上海今晚五星");
    }

    @Test
    void scenarioTwoReclassificationFromDownstreamToLayer1() {
        stubSse("agent_biz", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"response_content\":\"重分类上下文\","
                        + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_L1\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));
        stubSse("agent_L1", List.of(
                "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"text\":\"重新分类：国内酒店\"}}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));

        VersatileAgentHandler l1Handler = new VersatileAgentHandler(legacyProps("agent_L1"));
        A2AEnabledServeOrchestrator l1Orch = new A2AEnabledServeOrchestrator(
                l1Handler, new InMemoryTaskStore(), mock(RemoteAgentCaller.class),
                mock(RemoteAgentCardResolver.class), new ActiveStreamRegistry(), "agent-L1");

        VersatileAgentHandler downstreamHandler = new VersatileAgentHandler(downstreamReclassifyProps());
        RemoteAgentCaller downstreamCaller = new InProcessRemoteAgentCaller(
                Map.of("agent_L1", asHandler(l1Orch)));
        A2AEnabledServeOrchestrator downstreamOrch = new A2AEnabledServeOrchestrator(
                downstreamHandler, new InMemoryTaskStore(), downstreamCaller,
                mock(RemoteAgentCardResolver.class), new ActiveStreamRegistry(), "agent-downstream");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c4");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "订国际酒店")));

        QueryResponse response = downstreamOrch.query(request);

        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("content")).isEqualTo("重新分类：国内酒店");
    }

    @Test
    void scenarioThreeExplicitUserInterrupt() {
        stubSse("agent_L1", List.of(
                "data: {\"event\":\"need_user_input\",\"data\":"
                        + "{\"question\":\"请提供入住日期\",\"input_schema\":\"date\","
                        + "\"resume_token\":\"tok-123\"}}",
                "data: {\"data\":{\"node_type\":\"End\"}}"
        ));

        VersatileAgentHandler l1Handler = new VersatileAgentHandler(interruptProps());
        A2AEnabledServeOrchestrator l1Orch = new A2AEnabledServeOrchestrator(
                l1Handler, new InMemoryTaskStore(), mock(RemoteAgentCaller.class),
                mock(RemoteAgentCardResolver.class), new ActiveStreamRegistry(), "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        QueryResponse response = l1Orch.query(request);

        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("_interrupt")).isInstanceOf(Map.class);
        Map<?, ?> interrupt = (Map<?, ?>) result.get("_interrupt");
        assertThat(interrupt.get("message")).isEqualTo("请提供入住日期");
        assertThat(interrupt.get("input_requirement")).isEqualTo("date");
        assertThat(interrupt.get("resume_token")).isEqualTo("tok-123");
    }

    private void stubSse(String agentSegment, List<String> sseLines) {
        StringBuilder body = new StringBuilder();
        for (String line : sseLines) {
            body.append(line).append("\n\n");
        }
        wireMock.stubFor(WireMock.post(WireMock.urlPathMatching(".*/agents/" + agentSegment + "/conversations/.*"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body.toString())));
    }

    private static AgentHandler asHandler(A2AEnabledServeOrchestrator orch) {
        return new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest request) {
                return orch.query(request);
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                orch.streamQuery(request, observer);
            }
        };
    }

    private VersatileProperties l1Props() {
        VersatileProperties p = baseThreeFieldProps("agent_L1");
        VersatileProperties.Intent hotel = new VersatileProperties.Intent();
        hotel.setId("intent_L1_hotel");
        hotel.setName("酒店");
        p.setIntents(List.of(hotel));
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_L2_hotel");
        p.getIntentAgentMapping().put("intent_L1_hotel", List.of(candidate));
        return p;
    }

    private VersatileProperties l2Props() {
        VersatileProperties p = baseThreeFieldProps("agent_L2");
        VersatileProperties.Intent dom = new VersatileProperties.Intent();
        dom.setId("intent_L2_hotel_domestic");
        dom.setName("国内酒店");
        p.setIntents(List.of(dom));
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_biz_hotel_domestic");
        p.getIntentAgentMapping().put("intent_L2_hotel_domestic", List.of(candidate));
        return p;
    }

    private VersatileProperties baseThreeFieldProps(String agentSegment) {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate("http://localhost:" + wireMockPort
                + "/v1/proj/agents/" + agentSegment + "/conversations/{conversation_id}");
        p.setResultNodeName("AnswerNode");
        p.getMessages().setRequired(true);
        addExtraction(p, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(p, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(p, "agent_id", "/custom_rsp_data/data/agent_id");
        return p;
    }

    private VersatileProperties downstreamReclassifyProps() {
        return baseThreeFieldProps("agent_biz");
    }

    private VersatileProperties legacyProps(String agentSegment) {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate("http://localhost:" + wireMockPort
                + "/v1/proj/agents/" + agentSegment + "/conversations/{conversation_id}");
        p.setResultNodeName("AnswerNode");
        p.getMessages().setRequired(true);
        return p;
    }

    private VersatileProperties interruptProps() {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate("http://localhost:" + wireMockPort
                + "/v1/proj/agents/agent_L1/conversations/{conversation_id}");
        p.setResultNodeName("AnswerNode");
        p.getMessages().setRequired(true);
        p.getInterrupt().setSignalMatch("need_user_input");
        p.getInterrupt().setPromptGet("/data/question");
        p.getInterrupt().setInputRequirementGet("/data/input_schema");
        p.getInterrupt().setResumeTokenGet("/data/resume_token");
        return p;
    }

    private static void addExtraction(VersatileProperties p, String match, String get) {
        VersatileProperties.ResultExtraction e = new VersatileProperties.ResultExtraction();
        e.setMatch(match);
        e.setGet(get);
        p.getResultExtractions().add(e);
    }
}
