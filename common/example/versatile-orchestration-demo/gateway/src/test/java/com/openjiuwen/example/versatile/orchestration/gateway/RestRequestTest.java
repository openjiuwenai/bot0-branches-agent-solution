/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit-tests the REST {@code /v1/query} body construction from the rebuilt EDPA envelope. Mirrors
 * the {@code agentcore-ext-remote-a2a-tool-demo} README contract: conversation_id/stream/user_id/
 * space_id/messages/agent_id/input/timeout/role_id/role_name/custom_data, with no {@code taskId}.
 */
class RestRequestTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GatewayProperties props(String userId, String spaceId) {
        // role/timeout supplied so EdpaEnvelopeBuilder stamps them; userId/spaceId vary per test.
        return new GatewayProperties(
                "http://plan-agent", GatewayProperties.Protocol.REST, Set.of(),
                "手机银行", "1", "300", userId, spaceId);
    }

    private static Map<String, Object> inputs(String query, String intent, String wapUserName) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("intent", intent);
        inputs.put("wap_userName", wapUserName);
        return inputs;
    }

    private static EdpaRequest full(String query, String intent) {
        return new EdpaEnvelopeBuilder(props(null, null))
                .build(inputs(query, intent, "张三"), "cid-1", "agent-9");
    }

    @Test
    void buildsRestBodyWithAllFieldsAndNoTaskId() throws Exception {
        EdpaRequest full = full("查尾号为4241的卡的余额", "查询账户余额");

        Map<String, Object> body = RestRequest.from(full, props(null, null)).body();

        // Reused EDPA fields.
        assertThat(body.get("conversation_id")).isEqualTo("cid-1");
        assertThat(body.get("agent_id")).isEqualTo("agent-9");
        assertThat(body.get("role_name")).isEqualTo("手机银行");
        assertThat(body.get("role_id")).isEqualTo("1");
        assertThat(body.get("timeout")).isEqualTo("300");
        assertThat(body.get("stream")).isEqualTo(true);
        // REST-only identity defaults when properties absent.
        assertThat(body.get("user_id")).isEqualTo("demo-user");
        assertThat(body.get("space_id")).isEqualTo("demo-space");
        // No taskId — REST resume is conversation-id routed.
        assertThat(body).doesNotContainKey("taskId");

        // messages[0].content is a stringified {"query","intent"}.
        java.util.List<?> messages = (java.util.List<?>) body.get("messages");
        assertThat(messages).hasSize(1);
        Map<?, ?> msg = (Map<?, ?>) messages.get(0);
        assertThat(msg.get("role")).isEqualTo("user");
        JsonNode content = MAPPER.readTree(String.valueOf(msg.get("content")));
        assertThat(content.get("query").asText()).isEqualTo("查尾号为4241的卡的余额");
        assertThat(content.get("intent").asText()).isEqualTo("查询账户余额");

        // input carries the two tiers the plan-agent reads.
        Map<?, ?> input = (Map<?, ?>) body.get("input");
        assertThat(input.get("query")).isEqualTo("查尾号为4241的卡的余额");
        assertThat(input.get("intent")).isEqualTo("查询账户余额");

        // custom_data.inputs round-trips the caller extras untouched.
        Map<?, ?> customInputs = (Map<?, ?>) ((Map<?, ?>) body.get("custom_data")).get("inputs");
        assertThat(customInputs.get("query")).isEqualTo("查尾号为4241的卡的余额");
        assertThat(customInputs.get("intent")).isEqualTo("查询账户余额");
        assertThat(customInputs.get("wap_userName")).isEqualTo("张三");
    }

    @Test
    void userIdAndSpaceIdComeFromPropertiesWhenPresent() {
        EdpaRequest full = full("hi", "");
        Map<String, Object> body = RestRequest.from(full, props("alice", "tenant-7")).body();
        assertThat(body.get("user_id")).isEqualTo("alice");
        assertThat(body.get("space_id")).isEqualTo("tenant-7");
    }

    @Test
    void blankIdentityFallsBackToDefaults() {
        EdpaRequest full = full("hi", "");
        Map<String, Object> body = RestRequest.from(full, props("  ", "")).body();
        assertThat(body.get("user_id")).isEqualTo("demo-user");
        assertThat(body.get("space_id")).isEqualTo("demo-space");
    }
}
