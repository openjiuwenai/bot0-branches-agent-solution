/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Mock A2A Gateway endpoint that forwards inbound JSON-RPC {@code message/send}
 * requests to the target runtime's {@code /v1/query} endpoint, enabling the
 * full L1→L2→业务 chain (L2 §6.2.1) to be exercised under the
 * {@code a2a-gateway.enabled=true} profile.
 *
 * <p>Activated by the {@code mock-a2a-gateway} profile. Listens on
 * {@code POST /a2a/{agentId}} — the URL shape that
 * {@code A2AGatewayCardResolver.resolveJsonRpcUrl} produces — and:
 * <ol>
 *   <li>Extracts the user query text from the JSON-RPC
 *       {@code params.message.parts[0].text} field</li>
 *   <li>Looks up the target runtime base URL from {@link #ROUTING} by
 *       {@code agentId}</li>
 *   <li>Forwards the query to {@code <targetBase>/v1/query} as a sync
 *       {@code QueryRequest} body, propagating the {@code userId} header as
 *       the body's {@code user_id} field so the target's ServeRequest carries
 *       the upstream user identity</li>
 *   <li>Wraps the target's {@code QueryResponse.result} into an
 *       {@code {"type":"answer","payload":{"content":"..."},"agent_id":"...",
 *       "intent_id":"..."}} envelope so the caller's
 *       {@code RemoteAgentAnswerExtractor} can capture the business text and
 *       the orchestrator can read {@code agent_id} for further forwarding</li>
 *   <li>Wraps the envelope in a JSON-RPC response with a
 *       {@code TASK_STATE_COMPLETED} task whose artifact carries the envelope
 *       as its part text</li>
 * </ol>
 *
 * <p>Inbound headers ({@code token}, {@code userId}, {@code versionNode},
 * {@code X-B3-*}, {@code X-Biz-Tag}) are logged at INFO for shell-script
 * verification of header propagation, but are NOT echoed in the response —
 * the response carries the target runtime's actual business output instead.
 *
 * <p>The routing table mirrors {@code card-resolver.local-mapping} in
 * {@code application-mock-versatile.yml}, mapping each {@code agentCard} to
 * the runtime process that hosts it. Ports match the e2e script defaults
 * (L1=8081, L2=8082, downstream=8083, default-wf=8085).
 *
 * @since 0.1.0
 */
@Profile("mock-a2a-gateway")
@RestController
public class MockA2AGatewayController {
    /**
     * agentCard → target runtime base URL. Mirrors the local-mapping in
     * application-mock-versatile.yml. The mock gateway uses this to route
     * inbound /a2a/{agentId} requests to the correct runtime process.
     */
    static final Map<String, String> ROUTING = Map.of(
            "agent_card_L2_hotel", "http://localhost:8082",
            "agent_card_L2_flight_a", "http://localhost:8082",
            "agent_card_L2_flight_b", "http://localhost:8082",
            "agent_card_L2_fallback", "http://localhost:8082",
            "agent_card_L2_default", "http://localhost:8085",
            "agent_card_biz_hotel_domestic", "http://localhost:8083",
            "agent_card_biz_hotel_international", "http://localhost:8083",
            "agent_card_biz_flight_domestic", "http://localhost:8083",
            "agent_L1", "http://localhost:8081");

    private static final Logger log = LoggerFactory.getLogger(MockA2AGatewayController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUERY_PATH = "/v1/query";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Handles an inbound mock A2A Gateway JSON-RPC request, forwarding it to
     * the mapped target runtime and returning a synthetic JSON-RPC completion.
     *
     * @param agentId the path variable agent identifier (mapped via {@link #ROUTING})
     * @param request the servlet request, source of propagated headers
     * @param body    the raw JSON-RPC request body (may be {@code null})
     * @return a {@code 200 OK} with JSON-RPC completion or error envelope
     */
    @PostMapping("/a2a/{agentId}")
    public ResponseEntity<String> handle(
            @PathVariable String agentId,
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        String query = extractMessageText(body);
        String contextId = extractContextId(body);
        String jsonRpcId = extractJsonRpcId(body);
        logInbound(agentId, query, contextId, request);

        String targetBase = ROUTING.get(agentId);
        if (targetBase == null) {
            log.error("Mock A2A Gateway: no routing entry for agentId={}", agentId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson(jsonRpcId, "no routing for agentId=" + agentId));
        }

        String targetUrl = targetBase + QUERY_PATH;
        String queryBody = buildQueryBody(contextId, query, request.getHeader("userId"));
        log.info("Mock A2A Gateway forwarding agentId={} -> {} body={}", agentId, targetUrl, queryBody);
        return forwardAndBuildResponse(agentId, targetUrl, queryBody, jsonRpcId);
    }

    private void logInbound(String agentId, String query, String contextId, HttpServletRequest request) {
        String token = request.getHeader("token");
        String userId = request.getHeader("userId");
        String versionNode = request.getHeader("versionNode");
        String traceId = request.getHeader("X-B3-TraceId");
        String spanId = request.getHeader("X-B3-SpanId");
        String parentSpanId = request.getHeader("X-B3-ParentSpanId");
        String sampled = request.getHeader("X-B3-Sampled");
        String bizTag = request.getHeader("X-Biz-Tag");

        log.info("Mock A2A Gateway inbound agentId={} query={} contextId={} tokenPresent={} userId={} "
                        + "versionNode={} traceId={} spanId={} parentSpanId={} sampled={} bizTag={}",
                agentId, query, contextId, token != null && !token.isBlank(), userId, versionNode,
                traceId, spanId, parentSpanId, sampled, bizTag);
        if (log.isDebugEnabled() && token != null) {
            log.debug("Mock A2A Gateway inbound token (debug only) len={}", token.length());
        }
    }

    private ResponseEntity<String> forwardAndBuildResponse(String agentId, String targetUrl,
            String queryBody, String jsonRpcId) {
        try {
            HttpRequest forwardReq = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(queryBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(forwardReq,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Mock A2A Gateway: target {} returned HTTP {} body={}",
                        targetUrl, response.statusCode(), response.body());
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson(jsonRpcId, "target HTTP " + response.statusCode()));
            }

            String envelope = buildAnswerEnvelope(response.body());
            String taskId = "task-mock-" + Math.floorMod(agentId.hashCode(), Integer.MAX_VALUE);
            String jsonRpcResponse = completedJson(jsonRpcId, taskId, envelope);
            log.info("Mock A2A Gateway outbound agentId={} envelopeLen={} taskId={}",
                    agentId, envelope.length(), taskId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonRpcResponse);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            log.error("Mock A2A Gateway: forward to {} failed", targetUrl, e);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson(jsonRpcId, "forward failed: " + e.getMessage()));
        }
    }

    private static String buildQueryBody(String contextId, String query, String userId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("conversation_id", contextId != null && !contextId.isBlank()
                ? contextId : "ctx-mock-" + System.currentTimeMillis());
        body.put("stream", false);
        if (userId != null && !userId.isBlank()) {
            body.put("user_id", userId);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", query);
        return body.toString();
    }

    private static String buildAnswerEnvelope(String targetResponseBody) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(targetResponseBody);
        JsonNode result = root.path("result");

        String content = "";
        String agentId = "";
        String intentId = "";

        if (result.isObject()) {
            content = firstNonBlank(result, "content", "response_content", "text", "output");
            agentId = textOrEmpty(result, "agent_id");
            intentId = textOrEmpty(result, "intent_id");
        } else if (result.isTextual()) {
            content = result.asText();
        } else {
            log.warn("Mock A2A Gateway: unexpected result node type={}", result.getNodeType());
        }

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("type", "answer");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("content", content);
        if (!agentId.isEmpty()) {
            envelope.put("agent_id", agentId);
        }
        if (!intentId.isEmpty()) {
            envelope.put("intent_id", intentId);
        }
        return envelope.toString();
    }

    private static String firstNonBlank(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = textOrEmpty(node, key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String textOrEmpty(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isTextual()) {
            return value.asText();
        }
        return "";
    }

    private static String extractMessageText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode parts = MAPPER.readTree(body).path("params").path("message").path("parts");
            if (parts.isArray() && parts.size() > 0) {
                JsonNode text = parts.get(0).path("text");
                if (text.isTextual()) {
                    return text.asText();
                }
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return "";
    }

    private static String extractContextId(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(body).path("params").path("message").path("contextId");
            if (node.isTextual()) {
                return node.asText();
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return "";
    }

    private static String extractJsonRpcId(String body) {
        if (body == null || body.isBlank()) {
            return "1";
        }
        try {
            JsonNode node = MAPPER.readTree(body).path("id");
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isNumber()) {
                return node.asText();
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return "1";
    }

    private static String completedJson(String id, String taskId, String envelopeText) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        ObjectNode task = root.putObject("result").putObject("task");
        task.put("id", taskId);
        task.put("contextId", "ctx-mock");
        task.putObject("status").put("state", "TASK_STATE_COMPLETED");
        ObjectNode artifact = task.putArray("artifacts").addObject();
        artifact.put("artifactId", "art-mock");
        ObjectNode part = artifact.putArray("parts").addObject();
        part.put("text", envelopeText);
        part.putObject("metadata");
        part.put("filename", "");
        part.put("mediaType", "");
        return writeJson(root);
    }

    private static String errorJson(String id, String message) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        ObjectNode error = root.putObject("error");
        error.put("code", -32603);
        error.put("message", message);
        return writeJson(root);
    }

    private static String writeJson(ObjectNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON-RPC response", e);
        }
    }
}
