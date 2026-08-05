/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verifies the A2A seam delegates in order: translate the EDPA envelope (with resume taskId) →
 * stream the resulting {@link MessageSendParams} to the plan-agent. The collaborators are mocked;
 * their own logic is covered by {@link EdpaRequestTranslatorTest} and the SSE handling lives in
 * {@link A2aStreamingClient}.
 */
class A2aPlanAgentClientTest {
    @Test
    void translateThenStream() throws Exception {
        EdpaRequestTranslator translator = mock(EdpaRequestTranslator.class);
        A2aStreamingClient streaming = mock(A2aStreamingClient.class);
        OutputStream out = mock(OutputStream.class);

        EdpaRequest full = new EdpaRequest(Map.of("conversation_id", "cid", "input", Map.of("query", "hi")));
        Map<String, Object> headers = Map.of("stream", "true");
        Map<String, Object> query = Map.of("type", "controller");
        MessageSendParams stub = stubParams("cid", "hi");
        when(translator.translate(eq(full), eq(headers), eq(query))).thenReturn(stub);

        new A2aPlanAgentClient(translator, streaming).forward(full, headers, query, out);

        // The translator runs first (it owns resume-state injection), then the streaming client
        // receives exactly the translated params and the caller's output stream.
        verify(translator).translate(full, headers, query);
        verify(streaming).streamPost(same(stub), same(out));
        verifyNoMoreInteractions(translator, streaming);
    }

    @Test
    void nullHeadersAndQueryAreForwardedAsIs() throws Exception {
        EdpaRequestTranslator translator = mock(EdpaRequestTranslator.class);
        A2aStreamingClient streaming = mock(A2aStreamingClient.class);
        OutputStream out = mock(OutputStream.class);

        EdpaRequest full = new EdpaRequest(Map.of("conversation_id", "cid"));
        MessageSendParams stub = stubParams("cid", "");
        when(translator.translate(any(), any(), any())).thenReturn(stub);

        new A2aPlanAgentClient(translator, streaming).forward(full, null, null, out);

        verify(translator).translate(full, null, null);
        verify(streaming).streamPost(stub, out);
    }

    private static MessageSendParams stubParams(String contextId, String text) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(text)))
                .build();
        return MessageSendParams.builder().message(message).build();
    }
}
