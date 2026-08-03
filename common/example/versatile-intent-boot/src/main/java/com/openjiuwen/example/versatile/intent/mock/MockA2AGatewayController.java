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
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 末端业务卡 → versatile mock agentId 映射（mock 内硬编码，非生产路由）。
     * 直链隧道收到这些卡时，gateway 直接转发到 versatile mock 的
     * {@code /v1/proj/agents/{agentId}/conversations/{cid}} 端点（而非中间跳所用的
     * {@code /v1/query}），并把 serve 协议 body 翻译成 versatile {@code {inputs:...}} body，
     * 从而让业务的原始 versatile SSE 事件不经业务终端 handler 直接到达 client。
     */
    private static final Map<String, String> VERSATILE_TERMINAL_AGENTS = Map.of(
            "agent_card_biz_hotel_domestic", "agent_biz",
            "agent_card_biz_hotel_international", "agent_biz",
            "agent_card_biz_flight_domestic", "agent_biz");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Instance-level routing table. Defaults to {@link #ROUTING} via the
     * no-arg constructor; the package-private constructor allows tests to
     * inject a custom routing map (e.g. pointing at an in-process target).
     */
    private final Map<String, String> routing;

    private final Set<String> passthroughCards;

    /**
     * Spring constructor. Builds the effective routing table by overlaying
     * configured overrides onto the static {@link #ROUTING} defaults, and
     * reads the passthrough card set. With empty config (the default for
     * {@code local-e2e-a2a-gateway.sh}) behavior is unchanged.
     *
     * @param properties mock-a2a-gateway 配置（routing 覆盖 + passthrough-cards）
     */
    @Autowired
    public MockA2AGatewayController(MockA2aGatewayProperties properties) {
        Map<String, String> effective = new LinkedHashMap<>(ROUTING);
        if (properties != null && properties.getRouting() != null) {
            effective.putAll(properties.getRouting());
        }
        this.routing = effective;
        this.passthroughCards = properties == null || properties.getPassthroughCards() == null
                ? Set.of() : Set.copyOf(properties.getPassthroughCards());
    }

    /**
     * Test-friendly constructor that overrides the routing table.
     *
     * @param routing agentCard → target runtime base URL map
     */
    MockA2AGatewayController(Map<String, String> routing) {
        this(routing, Set.of());
    }

    /**
     * Test-friendly constructor with explicit routing and passthrough set.
     *
     * @param routing agentCard → target runtime base URL map
     * @param passthroughCards 走 A2A 原生透传的末端业务卡集合（null 视为空集）
     */
    MockA2AGatewayController(Map<String, String> routing, Set<String> passthroughCards) {
        this.routing = routing;
        this.passthroughCards = passthroughCards == null ? Set.of() : Set.copyOf(passthroughCards);
    }

    /**
     * Handles an inbound mock A2A Gateway JSON-RPC request, forwarding it to
     * the mapped target runtime and returning a synthetic JSON-RPC completion.
     *
     * @param agentId the path variable agent identifier (mapped via {@link #routing})
     * @param request the servlet request, source of propagated headers
     * @param body    the raw JSON-RPC request body (may be {@code null})
     * @param response the servlet response, written directly on the passthrough path
     * @return a {@code 200 OK} with JSON-RPC completion or error envelope
     * @throws IOException if forwarding to the target runtime fails
     */
    @PostMapping("/a2a/{agentId}")
    public ResponseEntity<String> handle(
            @PathVariable String agentId,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody(required = false) String body) throws IOException {
        String query = extractMessageText(body);
        String contextId = extractContextId(body);
        String jsonRpcId = extractJsonRpcId(body);
        logInbound(agentId, query, contextId, request);

        String targetBase = routing.get(agentId);
        if (targetBase == null) {
            log.error("Mock A2A Gateway: no routing entry for agentId={}", agentId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson(jsonRpcId, "no routing for agentId=" + agentId));
        }

        if (passthroughCards.contains(agentId)) {
            log.info("Mock A2A Gateway PASSTHROUGH agentId={} -> {}/a2a/", agentId, targetBase);
            new A2aPassthroughForwarder().forward(targetBase, agentId, body, response);
            return ResponseEntity.ok().build();
        }

        String targetUrl = targetBase + QUERY_PATH;
        String queryBody = buildQueryBody(contextId, query, request.getHeader("userId"));
        log.info("Mock A2A Gateway forwarding agentId={} -> {} body={}", agentId, targetUrl, queryBody);
        return forwardAndBuildResponse(agentId, targetUrl, queryBody, jsonRpcId);
    }

    /**
     * Direct-chain tunnel endpoint. Activated by the {@code X-Direct-Chain=true}
     * header (matched by Spring's {@code headers} attribute, taking precedence
     * over the no-header {@link #handle} mapping). Streams the target's raw
     * {@code data:} lines back to the response output stream as
     * {@code text/event-stream} without parsing or rewrapping.
     *
     * <p>Two forwarding modes (mock-only, hardcoded by {@link #VERSATILE_TERMINAL_AGENTS}):
     * <ul>
     *   <li><b>末端业务卡</b>（如 {@code agent_card_biz_hotel_domestic}）：gateway 直接转发到
     *       versatile mock 的 {@code /v1/proj/agents/{versatileAgentId}/conversations/{cid}}
     *       端点，并把 serve 协议 body（{@code {conversation_id,stream,messages}}）翻译成
     *       versatile {@code {inputs:{query,messages}}} body。业务原始 versatile SSE 事件
     *       不经任何业务终端 handler 直接透传给 client。</li>
     *   <li><b>中间跳卡</b>（如 {@code agent_card_L2_hotel}）：哑隧道，原样转发到
     *       {@code routing[agentId] + "/v1/query"}，body 不改。</li>
     * </ul>
     *
     * @param agentId  the path variable agent identifier (mapped via {@link #routing})
     * @param body     the raw inbound request body (may be {@code null})
     * @param response the servlet response used to stream SSE bytes
     * @throws IOException if the forward/stream is interrupted or fails
     */
    @PostMapping(value = "/a2a/{agentId}", headers = "X-Direct-Chain=true")
    public void tunnel(
            @PathVariable String agentId,
            @RequestBody(required = false) String body,
            HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        String targetBase = routing.get(agentId);
        if (targetBase == null) {
            log.error("Mock A2A Gateway tunnel: no routing for agentId={}", agentId);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "no routing for agentId=" + agentId);
            return;
        }
        String forwardBody = body != null && !body.isBlank() ? body : "{}";
        String targetUrl;
        String versatileAgent = VERSATILE_TERMINAL_AGENTS.get(agentId);
        if (versatileAgent != null) {
            // 末端业务卡：直连 versatile mock，翻译 body + 拼 versatile 路径
            String[] rewritten = rewriteToVersatile(targetBase, versatileAgent, forwardBody);
            targetUrl = rewritten[0];
            forwardBody = rewritten[1];
        } else {
            // 中间跳：哑隧道，原样转发到 /v1/query
            targetUrl = targetBase + QUERY_PATH;
        }
        log.info("Mock A2A Gateway TUNNEL agentId={} -> {}", agentId, targetUrl);
        try {
            HttpRequest forwardReq = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(forwardBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> resp = httpClient.send(forwardReq,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var is = resp.body()) {
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.error("Mock A2A Gateway tunnel: target {} returned HTTP {}", targetUrl, resp.statusCode());
                    response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
                    return;
                }
                var out = response.getOutputStream();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        out.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new IOException("tunnel interrupted", e);
        }
    }

    /**
     * 把直链隧道收到的 serve 协议 body（{@code {conversation_id,stream,messages:[{role,content}]}}）
     * 翻译成 versatile 协议 body（{@code {inputs:{query,messages}}}），并拼出 versatile mock
     * URL {@code <targetBase>/v1/proj/agents/<versatileAgent>/conversations/<cid>}。
     *
     * <p>{@code query} 取 messages 中最后一条 {@code role=user} 的 content（支持纯文本与
     * {@code {query:"..."}} 结构两种形态）；{@code cid} 取 {@code conversation_id}，缺省回退
     * {@code "default"}。解析失败时尽力降级，不抛异常。
     *
     * @param targetBase     目标 runtime base URL（来自 routing）
     * @param versatileAgent versatile mock agentId（来自 {@link #VERSATILE_TERMINAL_AGENTS}）
     * @param serveBody      直链隧道收到的 serve 协议 body
     * @return {@code [targetUrl, versatileBody]}，长度恒为 2
     */
    private static String[] rewriteToVersatile(String targetBase, String versatileAgent, String serveBody) {
        String conversationId = "";
        String query = "";
        JsonNode messages = null;
        try {
            JsonNode root = MAPPER.readTree(serveBody);
            JsonNode cid = root.path("conversation_id");
            if (cid.isTextual()) {
                conversationId = cid.asText();
            }
            JsonNode msgs = root.path("messages");
            if (msgs.isArray() && msgs.size() > 0) {
                messages = msgs;
                query = extractLastUserQuery(msgs);
            }
        } catch (JsonProcessingException e) {
            log.warn("Mock A2A Gateway tunnel: failed to parse serve body for versatile rewrite: {}",
                    e.getMessage());
        }
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("query", query);
        inputs.set("messages", messages != null ? messages : MAPPER.createArrayNode());
        ObjectNode versatileBody = MAPPER.createObjectNode();
        versatileBody.set("inputs", inputs);
        String cid = conversationId.isBlank() ? "default" : conversationId;
        String targetUrl = targetBase + "/v1/proj/agents/" + versatileAgent + "/conversations/" + cid;
        return new String[] {targetUrl, versatileBody.toString()};
    }

    /**
     * 从 messages 数组中提取最后一条 {@code role=user} 消息的 query。
     *
     * @param msgs serve body 中的 messages 数组
     * @return 最后一条用户消息的 query；无匹配时返回空串
     */
    private static String extractLastUserQuery(JsonNode msgs) {
        String query = "";
        for (JsonNode m : msgs) {
            if (!"user".equals(m.path("role").asText())) {
                continue;
            }
            Optional<String> extracted = extractQueryFromContent(m.path("content"));
            if (extracted.isPresent()) {
                query = extracted.get();
            }
        }
        return query;
    }

    /**
     * 从单条消息 content 中提取 query。
     *
     * @param content serve body 中 message 的 content 节点
     * @return 提取到的 query；content 形态不支持时返回 {@link Optional#empty()} 表示不覆盖既有值
     */
    private static Optional<String> extractQueryFromContent(JsonNode content) {
        if (content.isTextual()) {
            return Optional.of(content.asText());
        } else if (content.isObject()) {
            JsonNode q = content.path("query");
            if (q.isTextual()) {
                return Optional.of(q.asText());
            }
            return Optional.empty();
        } else {
            return Optional.empty();
        }
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
