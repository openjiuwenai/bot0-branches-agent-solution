/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Controller-layer wiring only: the caller posts the minimal {@code custom_data} body
 * ({@code {"inputs":{...},"headers":{...}}}); the controller binds it, hands inputs + path-derived
 * {@code conversation_id}/{@code agent_id} to {@link EdpaEnvelopeBuilder}, then passes the rebuilt
 * full envelope + inbound headers + URL query params to {@link PlanAgentClient} and streams the
 * SSE reply. Builder and client are mocked; their own logic is covered by dedicated tests
 * ({@link EdpaEnvelopeBuilderTest}, {@link RestRequestTest}, {@link A2aPlanAgentClientTest}). The
 * controller is protocol-agnostic, so this test exercises the wiring shared by both A2A and REST.
 */
@WebMvcTest(GatewayController.class)
class GatewayControllerWebMvcTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EdpaEnvelopeBuilder envelopeBuilder;
    @MockitoBean
    private PlanAgentClient client;

    @Test
    void postsMinimalInputsAndStreamsSse() throws Exception {
        EdpaRequest full = new EdpaRequest(Map.of(
                "conversation_id", "test-session-001",
                "input", Map.of("query", "hi")));
        when(envelopeBuilder.build(any(), eq("test-session-001"), eq("fcbcd0ce-73b0-4097-a0cb-6286341f88f6")))
                .thenReturn(full);
        // Make the client write one content frame into the response output stream.
        doAnswer(inv -> {
            java.io.OutputStream os = inv.getArgument(3);
            os.write("data: {\"role\":\"agent\"}\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            return null;
        }).when(client).forward(any(), any(), any(), any());

        mvc.perform(post("/v1/{projectId}/agents/{agentId}/conversations/{conversationId}",
                        "mock_project_id", "fcbcd0ce-73b0-4097-a0cb-6286341f88f6", "test-session-001")
                        .param("type", "controller").param("workspace_id", "12")
                        .contentType("application/json")
                        .content("{\"inputs\":{\"query\":\"hi\",\"intent\":\"\"},"
                                + "\"headers\":{\"stream\":\"true\",\"x-language\":\"zh-cn\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        // Inputs → builder (with path-derived conversation_id/agent_id).
        verify(envelopeBuilder).build(
                argThat(inputs -> "hi".equals(inputs.get("query"))),
                eq("test-session-001"),
                eq("fcbcd0ce-73b0-4097-a0cb-6286341f88f6"));
        // Rebuilt envelope + inbound headers (stream carried) + URL query params (type/workspace_id)
        // → the selected PlanAgentClient (A2A or REST — opaque to the controller).
        verify(client).forward(
                eq(full),
                argThat(h -> "true".equals(h.get("stream"))),
                argThat(q -> "controller".equals(q.get("type")) && "12".equals(q.get("workspace_id"))),
                any());
    }
}
