/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Versatile request extraction rules.
 *
 * @since 2026-06-30
 */
class VersatileRequestExtractorTest {

    @Test
    void buildsRemoteRequestFromMessagesAndMetadata() {
        VersatileProperties properties = new VersatileProperties();
        properties.setUrlTemplate("https://example.test/conversations/{conversation_id}");
        properties.getHeadersTemplate().put("Accept", "text/event-stream");
        properties.getForwardHeaderWhitelist().add("x-user-id");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setMessages(List.of(Map.of(
                "role", "user",
                "content", Map.of("query", "new question", "intent", "knowledge_qa")
        )));
        request.setMetadata(Map.of(
                "body", Map.of("custom_data", new LinkedHashMap<>(Map.of(
                        "query", "old question",
                        "intent", "old_intent",
                        "city", "Shenzhen"
                ))),
                "headers", Map.of("x-user-id", "u-1", "authorization", "secret"),
                "query", Map.of("workspace_id", "override", "type", "controller")
        ));

        VersatileRequestExtractor.RemoteRequest remote =
                new VersatileRequestExtractor(properties).extract(request);

        assertThat(remote.url()).isEqualTo("https://example.test/conversations/c-1");
        assertThat(remote.headers()).containsEntry("Accept", "text/event-stream")
                .containsEntry("x-user-id", "u-1")
                .doesNotContainKey("authorization");
        assertThat(remote.params()).containsEntry("workspace_id", "override")
                .containsEntry("type", "controller");
        assertThat(remote.body()).containsEntry("inputs", Map.of(
                        "query", "new question",
                        "intent", "knowledge_qa"
                ))
                .containsEntry("query", "old question")
                .containsEntry("intent", "old_intent")
                .containsEntry("city", "Shenzhen");
        assertThat(remote.body()).doesNotContainKey("custom_data");
    }

    @Test
    void selectsEndpointByIntentWithoutChangingRequestRules() {
        VersatileProperties properties = new VersatileProperties();
        properties.setUrlTemplate("https://example.test/default/{conversation_id}");
        VersatileProperties.Endpoint endpoint = new VersatileProperties.Endpoint();
        endpoint.setIntent("booking");
        endpoint.setUrlTemplate("https://example.test/booking/{conversation_id}");
        properties.getEndpoints().add(endpoint);

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-2");
        request.setMessages(List.of(Map.of(
                "role", "user",
                "content", "{\"query\":\"book hotel\",\"intent\":\"booking\"}"
        )));
        request.setMetadata(Map.of("body", Map.of("custom_data", Map.of("city", "Shanghai"))));

        VersatileRequestExtractor.RemoteRequest remote =
                new VersatileRequestExtractor(properties).extract(request);

        assertThat(remote.url()).isEqualTo("https://example.test/booking/c-2");
        assertThat(remote.body()).containsEntry("inputs", Map.of(
                        "query", "book hotel",
                        "intent", "booking"
                ))
                .containsEntry("city", "Shanghai");
        assertThat(remote.body()).doesNotContainKeys("query", "intent", "custom_data");
    }

    @Test
    void usesLastUserQueryFallbackWithoutReadingQueryFromCustomData() {
        VersatileProperties properties = new VersatileProperties();
        properties.setUrlTemplate("https://example.test/default/{conversation_id}");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-3");
        request.setMessages(List.of(Map.of("role", "user", "content", "plain question")));
        request.setMetadata(Map.of("body", Map.of("custom_data", Map.of(
                "query", "custom query",
                "city", "Beijing"
        )), "query", Map.of("channel", "web")));

        VersatileRequestExtractor.RemoteRequest remote =
                new VersatileRequestExtractor(properties).extract(request);

        assertThat(remote.body()).containsEntry("inputs", Map.of(
                        "query", "plain question"
                ))
                .containsEntry("query", "custom query")
                .containsEntry("city", "Beijing")
                .doesNotContainKeys("intent", "custom_data");
        assertThat(remote.params()).containsEntry("channel", "web");
    }

    @Test
    void headersTemplateOverridesForwardedHeadersWithSameName() {
        VersatileProperties properties = new VersatileProperties();
        properties.setUrlTemplate("https://example.test/default/{conversation_id}");
        properties.getHeadersTemplate().put("x-user-id", "static-user");
        properties.getHeadersTemplate().put("Accept", "text/event-stream");
        properties.getForwardHeaderWhitelist().add("x-user-id");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-4");
        request.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "q"))));
        request.setMetadata(Map.of(
                "body", Map.of("custom_data", Map.of()),
                "headers", Map.of("x-user-id", "runtime-user")
        ));

        VersatileRequestExtractor.RemoteRequest remote =
                new VersatileRequestExtractor(properties).extract(request);

        assertThat(remote.headers()).containsEntry("x-user-id", "static-user")
                .containsEntry("Accept", "text/event-stream");
    }
}
