/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2AGatewayRemoteAgentCallerTest {
    private A2AGatewayProperties props;

    @BeforeEach
    void setUp() {
        props = new A2AGatewayProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://gateway.example.com");
        props.setJsonRpcPath("/{agentCard}/a2a");
    }

    @Test
    void appendsResponseContentAsAssistantMessageWhenPresent() {
        ServeRequest original = new ServeRequest();
        original.setConversationId("c-1");
        original.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        ServeRequest forwarded = A2AGatewayRemoteAgentCaller.buildForwardedServeRequest(
                original, "一层输出");

        assertThat(forwarded.getMessages()).hasSize(2);
        assertThat(forwarded.getMessages().get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "订酒店");
        assertThat(forwarded.getMessages().get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "一层输出");
        assertThat(forwarded.lastUserQuery()).isEqualTo("订酒店");
    }

    @Test
    void doesNotAppendWhenResponseContentIsNull() {
        ServeRequest original = new ServeRequest();
        original.setConversationId("c-1");
        original.setMessages(List.of(Map.of("role", "user", "content", "hi")));

        ServeRequest forwarded = A2AGatewayRemoteAgentCaller.buildForwardedServeRequest(
                original, null);

        assertThat(forwarded.getMessages()).hasSize(1);
        assertThat(forwarded.lastUserQuery()).isEqualTo("hi");
    }

    @Test
    void preservesConversationIdUserIdTenantIdMetadata() {
        ServeRequest original = new ServeRequest();
        original.setConversationId("c-1");
        original.setUserId("u-1");
        original.setSpaceId("s-1");
        original.setTenantId("t-1");
        original.setStream(true);
        original.setMessages(List.of(Map.of("role", "user", "content", "hi")));
        original.setMetadata(Map.of("k", "v"));

        ServeRequest forwarded = A2AGatewayRemoteAgentCaller.buildForwardedServeRequest(
                original, "context");

        assertThat(forwarded.getConversationId()).isEqualTo("c-1");
        assertThat(forwarded.getUserId()).isEqualTo("u-1");
        assertThat(forwarded.getSpaceId()).isEqualTo("s-1");
        assertThat(forwarded.getTenantId()).isEqualTo("t-1");
        assertThat(forwarded.isStream()).isTrue();
        assertThat(forwarded.getMetadata()).containsEntry("k", "v");
    }

    @Test
    void supportedReturnsTrueForAnyNonBlankAgentId() {
        A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props,
                new A2AGatewayCardResolver(props));
        assertThat(caller.supported("agent_card_L2_hotel")).isTrue();
        assertThat(caller.supported("")).isFalse();
    }

    @Test
    void doesNotAppendWhenResponseContentIsBlank() {
        ServeRequest original = new ServeRequest();
        original.setConversationId("c-1");
        original.setMessages(List.of(Map.of("role", "user", "content", "hi")));

        ServeRequest forwarded = A2AGatewayRemoteAgentCaller.buildForwardedServeRequest(
                original, "   ");

        assertThat(forwarded.getMessages()).hasSize(1);
    }
}
