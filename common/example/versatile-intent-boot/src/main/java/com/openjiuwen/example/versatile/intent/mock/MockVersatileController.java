/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock Versatile SSE endpoint for local 联调 (L2 §5.5.3 方案 B).
 *
 * <p>Activated by the {@code mock-versatile} profile. Matches the Versatile
 * URL pattern {@code /v1/proj/agents/{agentId}/conversations/{conversationId}}
 * and returns canned SSE responses that drive the L2 §6.2 scenarios:
 * <ul>
 *   <li>Scenario 1 (query contains "订酒店", default): L1→L2→downstream full chain.</li>
 *   <li>Scenario 2 (query contains "重分类"): downstream→L1 reclassification.</li>
 *   <li>Scenario 3 (query contains "中断"): L1 explicit user interrupt.</li>
 *   <li>Scenario 4 (query contains "意图不明"): L2 returns {@code intent_id="1"}
 *       (ambiguous). L2 Adapter self-heals via {@code a2a_delegate} to
 *       {@code agent_card_L2_default}, which routes to the default-wf process
 *       ({@code agent_L2_default}) returning a fallback business answer.</li>
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

    /**
     * Counts invocations of {@link #mockVersatile} for use by E2E tests that
     * verify the route cache skips L1 on the second turn of a conversation.
     * Incremented at the entry of {@link #mockVersatile} (before scenario
     * selection) so every HTTP hit is recorded regardless of which canned
     * scenario the mock serves.
     */
    private final AtomicInteger l1InvocationCount = new AtomicInteger();

    /**
     * Handles a mock Versatile SSE request, selecting a canned response by query keyword.
     *
     * @param agentId        the path variable agent identifier
     * @param conversationId the path variable conversation identifier
     * @param body           the raw request body (may be {@code null})
     * @return a {@code 200 OK} with {@code text/event-stream} body containing canned SSE
     */
    @PostMapping("/v1/proj/agents/{agentId}/conversations/{conversationId}")
    public ResponseEntity<String> mockVersatile(
            @PathVariable String agentId,
            @PathVariable String conversationId,
            @RequestBody(required = false) String body) {
        l1InvocationCount.incrementAndGet();
        String query = extractQuery(body);
        boolean hasAssistant = hasAssistantMessage(body);
        String sse = cannedSse(agentId, query, conversationId, hasAssistant);
        log.info("Mock Versatile agentId={} conversationId={} query={} hasAssistant={} -> {} bytes SSE",
                agentId, conversationId, query, hasAssistant, sse.length());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sse);
    }

    /**
     * Returns the number of times {@link #mockVersatile} has been invoked since
     * the last {@link #resetCounters()} call. Used by E2E route-cache tests to
     * assert that L1 is skipped on cache hits.
     *
     * @return the current invocation count
     */
    public int getL1InvocationCount() {
        return l1InvocationCount.get();
    }

    /**
     * Resets the invocation counter to zero. Test setup calls this before each
     * scenario to make assertions deterministic.
     */
    public void resetCounters() {
        l1InvocationCount.set(0);
    }

    private static String extractQuery(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode queryNode = root.path("inputs").path("query");
            return queryNode.isTextual() ? queryNode.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Returns true if the request body's {@code inputs.messages} array contains
     * at least one entry with {@code role="assistant"}. Used by the ambiguous
     * scenario to distinguish the first L1/L2 call (only user messages) from
     * the L1 reclassify retry (user + assistant messages appended by the
     * {@code ReclassifyServeOrchestrator} decorator).
     *
     * @param body the raw request body (may be {@code null})
     * @return true if an assistant message is present
     */
    private static boolean hasAssistantMessage(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode messages = MAPPER.readTree(body).path("inputs").path("messages");
            // VersatileRequestExtractor serializes messages as a JSON string
            // (OBJECT_MAPPER.writeValueAsString(list)), so the node may be
            // textual rather than an array. Parse it again when that happens.
            if (messages.isTextual()) {
                messages = MAPPER.readTree(messages.asText());
            }
            if (!messages.isArray()) {
                return false;
            }
            for (JsonNode msg : messages) {
                JsonNode role = msg.path("role");
                if (role.isTextual() && "assistant".equals(role.asText())) {
                    return true;
                }
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return false;
    }

    private static String cannedSse(String agentId, String query, String conversationId, boolean hasAssistant) {
        if (query.contains("中断")) {
            return interruptSse();
        }
        if (query.contains("重分类")) {
            return reclassifySse(agentId);
        }
        if (query.contains("意图不明")) {
            return ambiguousSse(agentId, hasAssistant);
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

    /**
     * Scenario 4 canned SSE: L2 returns {@code intent_id="1"} (ambiguous).
     * L2 Adapter detects this and either self-heals via {@code a2a_delegate}
     * to {@code agent_card_L2_default} (when default-workflow is configured)
     * or produces a {@code TYPE_CHUNK} answer envelope carrying
     * {@code intent_id} (when default-workflow is absent, triggering L1
     * reclassify via the {@code ReclassifyServeOrchestrator} decorator).
     *
     * <p>On the L1 reclassify retry, the {@code ReclassifyServeOrchestrator}
     * decorator appends an assistant message (L2's previous response_content)
     * to the ServeRequest. The mock detects this via {@code hasAssistant}:
     * <ul>
     *   <li>{@code agent_L1} + {@code hasAssistant=true}: returns a three-field
     *       answer pointing directly to {@code agent_card_biz_hotel_domestic},
     *       skipping L2 and routing to downstream so the reclassify loop
     *       terminates with a business result.</li>
     *   <li>{@code agent_L2} + {@code hasAssistant=true}: returns a three-field
     *       answer (used by unit tests that re-invoke L2 directly).</li>
     * </ul>
     *
     * @param agentId       the agent identifier from the URL path
     * @param hasAssistant  true if the request messages contain an assistant entry
     * @return canned SSE response for the ambiguous scenario
     */
    private static String ambiguousSse(String agentId, boolean hasAssistant) {
        return switch (agentId) {
            case "agent_L1" -> hasAssistant
                    ? sse(
                            "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                                    + "{\"node_type\":\"QA\",\"response_content\":\"L1重识别完成\","
                                    + "\"intent_id\":\"intent_L2_hotel_domestic\","
                                    + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}",
                            "{\"data\":{\"node_type\":\"End\"}}")
                    : sse(
                            "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                                    + "{\"node_type\":\"QA\",\"response_content\":\"L1酒店意图\","
                                    + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}",
                            "{\"data\":{\"node_type\":\"End\"}}");
            case "agent_L2" -> hasAssistant
                    ? sse(
                            "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                                    + "{\"node_type\":\"QA\",\"response_content\":\"国内酒店已识别\","
                                    + "\"intent_id\":\"intent_L2_hotel_domestic\","
                                    + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}",
                            "{\"data\":{\"node_type\":\"End\"}}")
                    : sse(
                            "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                                    + "{\"node_type\":\"QA\",\"response_content\":\"无法确定国内/国际酒店\","
                                    + "\"intent_id\":\"1\"}}}",
                            "{\"data\":{\"node_type\":\"End\"}}");
            case "agent_L2_default" -> sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"text\":\"默认工作流兜底：转人工客服\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
            case "agent_biz" -> sse(
                    "{\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                            + "{\"node_type\":\"QA\",\"text\":\"酒店预订成功：上海今晚五星\"}}}",
                    "{\"data\":{\"node_type\":\"End\"}}");
            default -> sse("{\"data\":{\"node_type\":\"End\"}}");
        };
    }
}
