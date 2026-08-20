/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.handoff.mock;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock Versatile controller for the FEAT-002 intent-handoff demo (L2 spec §7.2
 * 场景旅程验收). Activated by the {@code mock-controller} profile.
 *
 * <p>Emits the real controller wire format: the two handoff signals are Dify-style
 * {@code message} SSE events, production samples verbatim (only the intent value
 * and {@code createdTime} vary per execution):
 * <ul>
 *   <li>L1 意图转调 — {@code node_name="意图返回"}, intent id at
 *       {@code data.summary} (e.g. {@code "3"}).</li>
 *   <li>L2 不在范围退回 — {@code node_name="不在范围"},
 *       {@code data.summary="不在范围"}.</li>
 * </ul>
 * 业务答案/终态/中断事件仍为 {@code node_finished} / {@code workflow_finished} /
 * {@code need_user_input} 形态（FEAT-002 基线提取依赖）。L1 意图分支为真流式：
 * 意图帧 flush 后延迟 {@value #INTENT_TAIL_DELAY_MS}ms 才发终态（模拟真实控制器
 * 在意图消息后继续执行工作流，e2e 断言 L1 等执行完成才 A2A 转发）。
 *
 * <p>Scenario selection is by {@code inputs.query} keyword (stateless except a
 * per-conversation invocation counter used by the 二级退回一级 journey, where the
 * re-invoked L1 must recognize locally instead of handing off again):
 *
 * <table border="1">
 *   <tr><th>query contains</th><th>agent_L1_controller</th><th>agent_L2_controller</th></tr>
 *   <tr><td>(default)</td><td>本地业务答案（无转调）</td><td>本域业务答案</td></tr>
 *   <tr><td>转调</td><td>意图返回 "3"</td><td>本域业务答案</td></tr>
 *   <tr><td>退回</td><td>首次: 意图返回 "3"；再次(同会话): 本地业务答案</td><td>不在范围</td></tr>
 *   <tr><td>循环</td><td>始终 意图返回 "3"</td><td>始终 不在范围</td></tr>
 *   <tr><td>补充信息</td><td>意图返回 "3"</td><td>need_user_input 中断</td></tr>
 *   <tr><td>超时</td><td>意图返回 "3"</td><td>延迟 10s 后本域答案</td></tr>
 *   <tr><td>无目标</td><td>意图返回 "99"</td><td>—</td></tr>
 *   <tr><td>越权</td><td>意图返回 "6"</td><td>—</td></tr>
 *   <tr><td>不可达</td><td>意图返回 "5"</td><td>—</td></tr>
 *   <tr><td>异常</td><td>exception 事件</td><td>—</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Profile("mock-controller")
@RestController
public class MockControllerController {
    private static final Logger log = LoggerFactory.getLogger(MockControllerController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Versatile agent id this mock serves for the L1 layer runtime. */
    public static final String L1_AGENT_ID = "agent_L1_controller";

    /** Versatile agent id this mock serves for the L2 layer runtime. */
    public static final String L2_AGENT_ID = "agent_L2_controller";

    /** L1 意图命中后控制器"继续执行"的时长，e2e 断言转发发生在此之前之后。 */
    private static final long INTENT_TAIL_DELAY_MS = 2_000L;

    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

    /**
     * Handles a mock controller SSE request, selecting a canned response by
     * agentId + query keyword.
     *
     * <p>L1 intent-handoff responses are真流式：意图事件先 flush，随后控制器
     * "继续执行" 2s 才发 workflow_finished —— L1 handler 必须等控制器执行完成
     * 才产出 a2a_delegate 转发，e2e 以日志时间戳断言先完成、后转发。
     *
     * @param agentId the path variable controller agent identifier
     * @param conversationId the path variable conversation identifier
     * @param body the raw request body (may be {@code null})
     * @return a {@code 200 OK} with {@code text/event-stream} streamed body
     */
    @PostMapping("/v1/proj/agents/{agentId}/conversations/{conversationId}")
    public ResponseEntity<StreamingResponseBody> mockVersatile(
            @PathVariable String agentId,
            @PathVariable String conversationId,
            @RequestBody(required = false) String body) {
        int count = invocations
                .computeIfAbsent(agentId + "|" + conversationId, k -> new AtomicInteger())
                .incrementAndGet();
        String query = extractQuery(body);
        log.info("Mock controller agentId={} conversationId={} invocation={} query={}",
                agentId, conversationId, count, query);
        if (L2_AGENT_ID.equals(agentId)) {
            return sse(out -> l2Response(out, query, count));
        }
        return sse(out -> l1Stream(out, query, count, conversationId));
    }

    /** L1 意图分支：意图帧 flush 后延迟 {@value #INTENT_TAIL_DELAY_MS}ms 再终态。 */
    private void l1Stream(OutputStream out, String query, int invocation, String conversationId)
            throws IOException {
        if (query.contains("异常")) {
            sseLines(out, exceptionEvent());
            return;
        }
        String intent = intentForQuery(query);
        if (intent != null && !(query.contains("退回") && invocation >= 2)) {
            sseLines(out, echoMessageEvent(intent, invocation), intentHandoffEvent(intent, invocation));
            out.flush();
            try {
                Thread.sleep(INTENT_TAIL_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("intent tail delay interrupted", ex);
            }
            log.info("L1 controller workflow finished conversationId={} invocation={}",
                    conversationId, invocation);
            sseLines(out, workflowEndEvent());
            return;
        }
        sseLines(out, answerEvent("一级本地业务答案：当前工作流已处理完成"), workflowEndEvent());
    }

    private void l2Response(OutputStream out, String query, int invocation) throws IOException {
        if (query.contains("退回") || query.contains("循环")) {
            sseLines(out, notInScopeEvent(invocation));
            return;
        }
        if (query.contains("补充信息")) {
            sseLines(out, interruptEvent(), workflowEndEvent());
            return;
        }
        if (query.contains("超时")) {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        sseLines(out, answerEvent("二级本域业务答案：机票已预订"), workflowEndEvent());
    }

    private static String intentForQuery(String query) {
        if (query.contains("无目标")) {
            return "99";
        }
        if (query.contains("越权")) {
            return "6";
        }
        if (query.contains("不可达")) {
            return "5";
        }
        if (query.contains("转调") || query.contains("退回") || query.contains("循环")
                || query.contains("补充信息") || query.contains("超时")) {
            return "3";
        }
        return null;
    }

    /**
     * L1 意图转调信号 — 生产报文样例原样（intent 值与 createdTime 随执行变化：
     * 真实控制器每条消息的 createdTime 不同，作为 dedup-key，重识别后的再次转调
     * 不会被判为重复消息）。
     */
    private static String intentHandoffEvent(String intentId, int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"" + intentId + "\","
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"意图返回\","
                + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                + "\"createdTime\":" + createdTime(invocation) + "}";
    }

    /**
     * 生产 SSE 会混入的意图回显帧（2026-08-19 确认）：同一 node_name、无 summary 键、
     * 意图值在 data.text。识别命中但提取路径缺失 → 分类器按非转调忽略（WARN），
     * 不报错不出站，交回基线。
     */
    private static String echoMessageEvent(String intentId, int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"" + intentId + "\","
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"意图返回\","
                + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                + "\"createdTime\":" + (createdTime(invocation) - 1) + "}";
    }

    /** L2 不在范围退回信号 — 生产报文样例原样（createdTime 随执行变化）。 */
    private static String notInScopeEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"不在范围\","
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"不在范围\","
                + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                + "\"createdTime\":" + createdTime(invocation) + "}";
    }

    /**
     * 本地/本域业务答案。{@code data.text} 与 {@code outputs.response} 同值：前者供
     * FEAT-002 基线 result-node-name 提取（{@code extractLegacyText} 读 {@code data.text}），
     * 后者保持真实报文形态。
     */
    private static String answerEvent(String text) {
        return "{\"event\":\"node_finished\",\"data\":{\"agent_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"node_id\":\"node_answer\",\"node_status\":\"node_finished\",\"parent_workflow_id\":\"\","
                + "\"status\":{\"code\":0,\"desc\":\"succeeded\"},\"node_name\":\"AnswerNode\",\"node_type\":\"QA\","
                + "\"inputs\":{},\"outputs\":{\"response\":\"" + text + "\"},\"text\":\"" + text + "\","
                + "\"start_time\":1787129556865,\"end_time\":1787129556867,"
                + "\"execution_id\":\"exec-answer\"},\"createdTime\":1787129556876}";
    }

    /** 控制器真实异常（不满足转调识别条件，走 FEAT-002 错误映射）。 */
    private static String exceptionEvent() {
        return "{\"event\":\"exception\",\"data\":{\"code\":500,\"message\":\"workflow node failed\","
                + "\"node_id\":\"node_failed\"}}";
    }

    /** L2 向最终用户请求补充信息（FEAT-002 基线中断信号，配置 interrupt.signal-match）。 */
    private static String interruptEvent() {
        return "{\"event\":\"need_user_input\",\"data\":{\"question\":\"请补充入住日期\","
                + "\"input_schema\":\"date\",\"resume_token\":\"tok-123\"}}";
    }

    private static String workflowEndEvent() {
        return "{\"event\":\"workflow_finished\",\"data\":{\"node_id\":\"node_end\",\"node_type\":\"End\","
                + "\"node_status\":\"node_finished\"}}";
    }

    /** 每次控制器执行生成新的 createdTime（真实控制器行为，作 dedup-key）。 */
    private static long createdTime(int invocation) {
        return 1787140547059L + invocation;
    }

    private static void sseLines(OutputStream out, String... dataLines) throws IOException {
        for (String line : dataLines) {
            out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ResponseEntity<StreamingResponseBody> sse(StreamingResponseBodyWriter writer) {
        StreamingResponseBody body = writer::writeTo;
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    @FunctionalInterface
    private interface StreamingResponseBodyWriter {
        void writeTo(OutputStream out) throws IOException;
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
}
