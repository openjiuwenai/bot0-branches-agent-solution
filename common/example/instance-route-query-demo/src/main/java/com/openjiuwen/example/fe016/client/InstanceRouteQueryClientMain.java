/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.example.fe016.agent.Fe016StubAgentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FEAT-016 AgentDemo — 命令行客户端。
 *
 * <p>仿照 {@code VersatileA2AClientMain} 的模式：一个带 {@code main()} 的独立
 * 客户端，用 {@link HttpClient} 调用运行中的
 * {@link com.openjiuwen.example.fe016.InstanceRouteQueryDemoApplication}，
 * 端到端演示 FEAT-016 的实例路由查询与 route-handle 解析，并复现其中存在的 Bug。
 *
 * <p>用法（先在另一个进程启动 demo 应用，再运行本客户端）：
 * <pre>
 *   # 默认连接 http://127.0.0.1:18090
 *   mvn -o -q exec:java -Dexec.mainClass=com.openjiuwen.example.fe016.client.InstanceRouteQueryClientMain
 *   # 或指定地址
 *   $env:RDC_BASE_URL="http://127.0.0.1:18090"
 * </pre>
 *
 * @since 0.1.0 (2026)
 */
public final class InstanceRouteQueryClientMain {
    private static final Logger log = LoggerFactory.getLogger(InstanceRouteQueryClientMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";
    private static final String AGENT_001 = "agent-001";
    private static final String SERVICE_001 = "svc-001";
    private static final String INSTANCE_1 = "10.0.0.1:8090";
    private static final String ROUTE_KEY = "/v1/query";
    private static final String CONTRACT_VER = "1.0.0";

    private final String baseUrl;
    private final HttpClient client;

    private InstanceRouteQueryClientMain(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("RDC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:18090";
        }
        log.info("FEAT-016 AgentDemo 客户端启动，目标: {}", baseUrl);
        new InstanceRouteQueryClientMain(baseUrl).run();
    }

    private void run() throws Exception {
        // 1) GET 实例列表 —— 调用方拿到 opaque routeHandle（不暴露 endpointUrl/routeKey/instanceId）
        JsonNode firstHandle = listInstances(TENANT_A, AGENT_001);

        // 2) 用服务端下发的 routeHandle 去 resolve —— 验证 opaque 句柄往返，拿到真实 endpointUrl
        String endpointUrl = resolveValid(firstHandle.asText(), TENANT_A);

        // 3) A2A 调用目标 Agent —— 真正的 AgentDemo 链路：用解析得到的 endpointUrl 发
        //    JSON-RPC SendStreamingMessage，回环命中本进程的 Fe016StubAgentHandler，
        //    验证 mock LLM 回显。这才是「客户端经注册中心路由后用 A2A 调用目标 Agent」。
        if (endpointUrl != null) {
            a2aCallAgent(endpointUrl, "hello from FEAT-016 AgentDemo");
        }

        // 4) 畸形 route-handle（缺少 v2: 前缀）—— 设计文档§7 要求 HTTP 400
        resolveMalformed();

        // 5) 跨租户解析（句柄内 tenant-A，请求 tenant-B）—— 设计文档§7 要求 HTTP 400
        resolveCrossTenant();

        // 6) 反枚举：查询不存在的 agent，应返回 200 + 空列表
        listInstances(TENANT_A, "nonexistent-agent");

        // 7) 错误码命名复现 —— 解析一条 entry 不存在的合法句柄，观察 error code
        resolveEntryNotFound();

        log.info("FEAT-016 AgentDemo 客户端结束。发现的 Bug 见上方 [BUG] 标记。");
    }

    private JsonNode listInstances(String tenantId, String agentId) throws Exception {
        String url = baseUrl + "/api/registry/instances/" + tenantId + "/" + agentId;
        HttpResponse<String> resp = send(url, "GET", null);
        log.info("GET {} → HTTP {}", url, resp.statusCode());
        JsonNode body = MAPPER.readTree(resp.body());
        log.info("响应: {}", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        if (resp.statusCode() == 200 && body.isArray() && !body.isEmpty()) {
            JsonNode handle = body.get(0).get("routeHandle");
            if (handle != null) {
                log.info("[OK] 第一个实例的 opaque routeHandle: {}", handle.asText());
                return handle;
            }
        }
        if (body.isArray() && body.isEmpty()) {
            log.info("[OK] 反枚举: agent={} 无实例 → 200 + 空列表（不区分不存在与无权限）", agentId);
        }
        return MAPPER.nullNode();
    }

    private String resolveValid(String routeHandle, String tenantId) throws Exception {
        String body = resolveBody(routeHandle, tenantId);
        HttpResponse<String> resp = postResolve(body);
        log.info("POST /route-handle/resolve (valid, tenant={}) → HTTP {}", tenantId, resp.statusCode());
        log.info("响应: {}", pretty(resp.body()));
        if (resp.statusCode() == 200) {
            JsonNode r = MAPPER.readTree(resp.body());
            String endpoint = r.path("endpointUrl").asText();
            log.info("[OK] RouteResolution{{}} endpointUrl={}, routeKey={}, contractVersion={}",
                    r.path("instanceId").asText(),
                    endpoint,
                    r.path("routeKey").asText(),
                    r.path("contractVersion").asText());
            return endpoint;
        }
        return null;
    }

    /**
     * 用解析得到的 endpointUrl 发 A2A JSON-RPC SendStreamingMessage，读 SSE 响应。
     *
     * <p>这是「真 AgentDemo」的客户端收尾链路：注册中心路由解析 → 拿到真实 endpoint
     * → 用 A2A 协议调用目标 Agent。SSE 帧格式 {@code data:{...}}，从中抽取 Agent 的
     * mock 回显文本，验证整条 A2A 往返。
     */
    private void a2aCallAgent(String endpointUrl, String userQuery) throws Exception {
        String a2aUrl = endpointUrl.endsWith("/a2a") ? endpointUrl : endpointUrl + "/a2a";
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("contextId", "fe016-demo-ctx-1");
        message.put("messageId", "fe016-demo-msg-1");
        message.putArray("parts").addObject().put("text", userQuery);
        ObjectNode params = MAPPER.createObjectNode();
        params.set("message", message);
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", "fe016-a2a-1");
        req.put("method", "SendStreamingMessage");
        req.set("params", params);
        String reqBody = MAPPER.writeValueAsString(req);

        HttpRequest request = HttpRequest.newBuilder(URI.create(a2aUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                .build();
        log.info("A2A POST {} (SendStreamingMessage, query=\"{}\")", a2aUrl, userQuery);
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("A2A 响应 HTTP {}", resp.statusCode());
        StringBuilder collected = new StringBuilder();
        for (String line : resp.body().split("\n", -1)) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String json = line.substring(5).trim();
            if (json.isBlank()) {
                continue;
            }
            collectText(json, collected);
            log.info("  SSE data: {}", json);
        }
        String reply = collected.toString();
        log.info("[OK] 目标 Agent mock 回复: {}", reply);
        if (reply.contains(Fe016StubAgentHandler.MOCK_REPLY_PREFIX)) {
            log.info("[OK] A2A 链路验证通过：客户端经注册中心路由解析 → A2A 调用 → 命中 Fe016StubAgentHandler");
        } else {
            log.warn("[WARN] 未在 SSE 响应中检测到 mock 前缀 {}，A2A 往返可能异常",
                    Fe016StubAgentHandler.MOCK_REPLY_PREFIX);
        }
    }

    private static void collectText(String json, StringBuilder out) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode result = root.path("result");
            collectTextIn(result, out);
        } catch (Exception ignored) {
            // 非 JSON 帧或解析失败：跳过，不影响其他帧
        }
    }

    private static void collectTextIn(JsonNode node, StringBuilder out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        // A2A 帧里文本可能出现在 text（parts[].text）或 content（QueryResponse 结果的 data.content）字段
        for (String field : new String[]{"text", "content"}) {
            JsonNode leaf = node.get(field);
            if (leaf != null && leaf.isTextual() && !leaf.asText().isEmpty()) {
                out.append(leaf.asText());
            }
        }
        for (JsonNode child : node) {
            collectTextIn(child, out);
        }
    }

    private void resolveMalformed() throws Exception {
        String body = resolveBody("not-a-valid-handle", TENANT_A);
        HttpResponse<String> resp = postResolve(body);
        log.info("POST /route-handle/resolve (malformed, 缺少 v2: 前缀) → HTTP {}", resp.statusCode());
        log.info("响应: {}", pretty(resp.body()));
        System.out.println("============================================================");
        System.out.println("[BUG #1] MALFORMED_ROUTE_HANDLE → HTTP 状态码错误");
        System.out.println("  期望（设计文档§7）: HTTP 400, error code: malformed_handle");
        System.out.println("  实际: HTTP " + resp.statusCode()
                + ", error code: " + errorField(resp.body()));
        System.out.println("  位置: RegistryApiExceptionHandler.mapFailureStatus()");
        System.out.println("        case \"ENTRY_NOT_FOUND\", \"MALFORMED_ROUTE_HANDLE\" -> HttpStatus.NOT_FOUND;");
        System.out.println("  原因: MALFORMED_ROUTE_HANDLE 被并入 ENTRY_NOT_FOUND 分支，映射到 404 而非 400");
        System.out.println("============================================================");
    }

    private void resolveCrossTenant() throws Exception {
        String handle = buildRouteHandle(TENANT_A, AGENT_001, SERVICE_001, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);
        String body = resolveBody(handle, TENANT_B);
        HttpResponse<String> resp = postResolve(body);
        log.info("POST /route-handle/resolve (cross-tenant: handle=tenant-A, body=tenant-B) → HTTP {}",
                resp.statusCode());
        log.info("响应: {}", pretty(resp.body()));
        System.out.println("============================================================");
        System.out.println("[BUG #2] TENANT_SCOPE_DENIED → HTTP 状态码错误");
        System.out.println("  期望（设计文档§7）: HTTP 400, error code: tenant_isolation_violation");
        System.out.println("  实际: HTTP " + resp.statusCode()
                + ", error code: " + errorField(resp.body()));
        System.out.println("  位置: RegistryApiExceptionHandler.mapFailureStatus()");
        System.out.println("        case \"CALLER_NOT_AUTHORIZED\", \"TENANT_SCOPE_DENIED\" -> HttpStatus.FORBIDDEN;");
        System.out.println("  原因: TENANT_SCOPE_DENIED 被并入 CALLER_NOT_AUTHORIZED 分支，映射到 403 而非 400");
        System.out.println("============================================================");
    }

    private void resolveEntryNotFound() throws Exception {
        // 合法格式但指向不存在的 instance —— 设计文档§7 要求 HTTP 404 entry_not_found
        String handle = buildRouteHandle(TENANT_A, AGENT_001, SERVICE_001, "10.0.0.99:8090",
                ROUTE_KEY, CONTRACT_VER);
        String body = resolveBody(handle, TENANT_A);
        HttpResponse<String> resp = postResolve(body);
        log.info("POST /route-handle/resolve (entry not found) → HTTP {}", resp.statusCode());
        log.info("响应: {}", pretty(resp.body()));
        String code = errorField(resp.body());
        System.out.println("------------------------------------------------------------");
        System.out.println("[观察] entry 不存在: HTTP " + resp.statusCode() + ", error code: " + code);
        System.out.println("  设计文档§7 期望: HTTP 404, error code: entry_not_found");
        System.out.println("  注: HTTP 404 状态码正确；error code 仍为大写 ENTRY_NOT_FOUND，");
        System.out.println("      与 malformed_handle / tenant_isolation_violation 的小写命名约定不一致");
        System.out.println("------------------------------------------------------------");
    }

    private HttpResponse<String> postResolve(String jsonBody) throws Exception {
        return send(baseUrl + "/api/registry/route-handle/resolve", "POST", jsonBody);
    }

    private HttpResponse<String> send(String url, String method, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (jsonBody != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String resolveBody(String routeHandle, String tenantId) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("routeHandle", routeHandle);
        node.put("tenantId", tenantId);
        node.putNull("context");
        return MAPPER.writeValueAsString(node);
    }

    private static String buildRouteHandle(String tenantId, String agentId, String serviceId,
                                           String instanceId, String routeKey, String contractVersion)
            throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tenantId", tenantId);
        node.put("agentId", agentId);
        node.put("serviceId", serviceId);
        node.put("instanceId", instanceId);
        node.put("routeKey", routeKey);
        node.put("contractVersion", contractVersion);
        byte[] json = MAPPER.writeValueAsBytes(node);
        return "v2:" + Base64.getEncoder().encodeToString(json);
    }

    private static String pretty(String json) {
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof java.util.List<?>) {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
            }
            return json;
        } catch (Exception ex) {
            return json;
        }
    }

    private static String errorField(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode err = node.get("error");
            if (err != null) {
                return err.asText();
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            Map<String, Object> copy = new LinkedHashMap<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                copy.put(e.getKey(), e.getValue().asText());
            }
            return copy.toString();
        } catch (Exception ex) {
            return json;
        }
    }
}
