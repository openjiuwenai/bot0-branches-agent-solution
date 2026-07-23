/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock Versatile SSE endpoint for local 联调 (L2 §5.5.3 方案 B).
 *
 * <p>Activated by the {@code mock-versatile} profile. Matches the Versatile
 * URL pattern {@code /v1/proj/agents/{agentId}/conversations/{conversationId}}
 * and returns canned SSE responses that drive the three L2 §6.2 scenarios:
 * <ul>
 *   <li>Scenario 1 (query contains "订酒店"): L1→L2→downstream full chain.</li>
 *   <li>Scenario 2 (query contains "重分类"): downstream→L1 reclassification.</li>
 *   <li>Scenario 3 (query contains "中断"): L1 explicit user interrupt.</li>
 * </ul>
 *
 * <p>The mock inspects {@code inputs.query} from the request body to select
 * the scenario, then varies the canned SSE by {@code agentId} within the
 * scenario. This keeps the mock stateless — no conversation tracking needed.
 *
 * @since 0.1.0
 */
@Profile("mock-versatile")
@RestController
public class MockVersatileController {
    private static final Logger log = LoggerFactory.getLogger(MockVersatileController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/v1/proj/agents/{agentId}/conversations/{conversationId}")
    public ResponseEntity<String> mockVersatile(
            @PathVariable String agentId,
            @PathVariable String conversationId,
            @RequestBody(required = false) String body) {
        String query = extractQuery(body);
        String sse = cannedSse(agentId, query, conversationId);
        log.info("Mock Versatile agentId={} conversationId={} query={} -> {} bytes SSE",
                agentId, conversationId, query, sse.length());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sse);
    }

    private static String extractQuery(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode queryNode = root.path("inputs").path("query");
            return queryNode.isTextual() ? queryNode.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String cannedSse(String agentId, String query, String conversationId) {
        if (query.contains("中断")) {
            return interruptSse();
        }
        if (query.contains("重分类")) {
            return reclassifySse(agentId);
        }
        return defaultChainSse(agentId);
    }

    private static String sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append("data: ").append(line).append("\n\n");
        }
        return sb.toString();
    }

    private static String defaultChainSse(String agentId) {
        return switch (agentId) {
            case "agent_L1" -> sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"response_content\":\"L1酒店意图\","
                            + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
            case "agent_L2" -> sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"response_content\":\"L2国内酒店\","
                            + "\"intent_id\":\"intent_L2_hotel_domestic\","
                            + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
            case "agent_biz" -> sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"text\":\"酒店预订成功：上海今晚五星\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
            default -> sse("{\"data\":{\"node_type\":\"End\"}}");
        };
    }

    private static String reclassifySse(String agentId) {
        if ("agent_biz".equals(agentId)) {
            return sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"response_content\":\"重分类上下文\","
                            + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_L1\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
        }
        return sse(
                "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                        + "{\"node_type\":\"QA\",\"text\":\"重新分类：国内酒店\"}}}",
                "{\"data\":{\"node_type\":\"End\"}}");
    }

    private static String interruptSse() {
        return sse(
                "{\"event\":\"need_user_input\",\"data\":"
                        + "{\"question\":\"请提供入住日期\",\"input_schema\":\"date\","
                        + "\"resume_token\":\"tok-123\"}}",
                "{\"data\":{\"node_type\":\"End\"}}");
    }
}
