/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies defensive request context copies.
 *
 * @since 0.1.0
 */
class CustomRestProtocolAdapterTest {
    @Test
    @SuppressWarnings("deprecation")
    void legacyCommandStoresConversationIdOnlyInMessage() {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("hello"))
                .messageId("message-id")
                .contextId("old-context")
                .metadata(Map.of("message", "kept"))
                .build();
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(Map.of("request", "kept"))
                .tenant("tenant")
                .build();

        var command = new CustomRestProtocolAdapter.A2ASendCommand(params, "business-conversation", true);

        assertThat(command.params().message().contextId()).isEqualTo("business-conversation");
        assertThat(command.params().message().messageId()).isEqualTo("message-id");
        assertThat(command.params().message().metadata()).containsEntry("message", "kept");
        assertThat(command.params().metadata()).containsEntry("request", "kept");
        assertThat(command.params().tenant()).isEqualTo("tenant");
        assertThat(command.stream()).isTrue();

        var unchanged = new CustomRestProtocolAdapter.A2ASendCommand(command.params(),
                "business-conversation", false);
        assertThat(unchanged.params()).isSameAs(command.params());
    }

    @Test
    void contextDefensivelyCopiesTopLevelCollectionsAndValues() {
        List<String> headerValues = new ArrayList<>(List.of("one"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("x-test", headerValues);
        Map<String, String> path = new LinkedHashMap<>(Map.of("id", "42"));
        Map<String, List<String>> query = new LinkedHashMap<>(Map.of("q", List.of("a", "b")));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("nested", Map.of("value", 1)));

        var context = new CustomRestProtocolAdapter.Context(headers, path, query, body);
        headers.clear();
        headerValues.add("two");
        path.clear();
        query.clear();
        body.clear();

        assertThat(context.headers()).containsEntry("x-test", List.of("one"));
        assertThat(context.pathVariables()).containsEntry("id", "42");
        assertThat(context.queryParams()).containsEntry("q", List.of("a", "b"));
        assertThat(context.body()).containsKey("nested");
        assertThatThrownBy(() -> context.headers().put("other", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
