/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.ServeRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests request identity, metadata allowlisting, and message selection.
 *
 * @since 2026-07-20
 */
class AgentScopeRequestMapperTest {
    private final AgentScopeRequestMapper mapper = new AgentScopeRequestMapper();

    @Test
    void mapsIdentityAndOnlyAllowlistedCallAttributes() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conversation-1");
        request.setUserId("user-1");
        request.setTenantId("tenant-1");
        request.setSpaceId("space-1");
        request.setMetadata(Map.of(
            "traceId", "trace-1",
            "requestId", "request-1",
            "authorization", "secret",
            "unlisted", Map.of("value", "not-forwarded")));

        RuntimeContext context = mapper.mapContext(request);

        assertThat(context.getSessionId()).isEqualTo("conversation-1");
        assertThat(context.getUserId()).isEqualTo("user-1");
        assertThat(context.<String>get("tenantId")).isEqualTo("tenant-1");
        assertThat(context.<String>get("spaceId")).isEqualTo("space-1");
        assertThat(context.<String>get("traceId")).isEqualTo("trace-1");
        assertThat(context.<String>get("requestId")).isEqualTo("request-1");
        assertThat(context.<String>get("authorization")).isNull();
        assertThat(context.<Object>get("unlisted")).isNull();
    }

    @Test
    void rejectsBlankConversationId() {
        ServeRequest request = new ServeRequest();
        request.setConversationId(" ");

        assertThatThrownBy(() -> mapper.mapContext(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conversationId");
    }

    @Test
    void mapsOnlyLatestEffectiveUserTurn() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(
            Map.of("role", "user", "content", "old question"),
            Map.of("role", "assistant", "content", "old answer"),
            Map.of("role", "user", "content", "current question"),
            Map.of("role", "assistant", "content", "client-side placeholder")));

        List<Msg> messages = mapper.mapCurrentTurn(request);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(messages.get(0).getTextContent()).isEqualTo("current question");
    }

    @Test
    void fallsBackToLastEffectiveMessageWhenNoUserRoleExists() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(Map.of("role", "custom", "content", "current input")));

        assertThat(mapper.mapCurrentTurn(request).get(0).getTextContent()).isEqualTo("current input");
    }

    @Test
    void rejectsRequestWithoutEffectiveMessage() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(Map.of("role", "user", "content", " ")));

        assertThatThrownBy(() -> mapper.mapCurrentTurn(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message");
    }
}
