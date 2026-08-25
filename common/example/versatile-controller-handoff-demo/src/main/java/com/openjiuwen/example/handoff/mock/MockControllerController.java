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
import java.util.Optional;
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
 *   <tr><td>无结果节点</td><td>意图返回 "3"</td><td>非结果节点内容帧透传 + End 节点收尾（无 result-node-name）</td></tr>
 *   <tr><td>退回</td><td>首次: 意图返回 "3"；再次(同会话): 本地业务答案</td><td>不在范围</td></tr>
 *   <tr><td>先答后退回</td><td>同"退回"</td><td>先 前置输出中间帧（透传泄漏），后 不在范围</td></tr>
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
    /** Versatile agent id this mock serves for the L1 layer runtime. */
    public static final String L1_AGENT_ID = "agent_L1_controller";

    /** Versatile agent id this mock serves for the L2 layer runtime. */
    public static final String L2_AGENT_ID = "agent_L2_controller";

    private static final Logger log = LoggerFactory.getLogger(MockControllerController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /**
     * L1 意图分支：意图帧 flush 后延迟 {@value #INTENT_TAIL_DELAY_MS}ms 再终态。
     *
     * @param out SSE 响应输出流
     * @param query 当前轮用户请求文本
     * @param invocation 本次会话的轮次计数（从 1 开始）
     * @param conversationId 会话标识
     * @throws IOException 写出 SSE 帧或意图尾延迟被中断时抛出
     */
    private void l1Stream(OutputStream out, String query, int invocation, String conversationId)
            throws IOException {
        if (query.contains("异常")) {
            sseLines(out, exceptionEvent());
            return;
        }
        Optional<String> intent = intentForQuery(query);
        if (intent.isPresent() && !(query.contains("退回") && invocation >= 2)) {
            sseLines(out, echoMessageEvent(intent.get(), invocation),
                    intentHandoffEvent(intent.get(), invocation));
            out.flush();
            try {
                Thread.sleep(INTENT_TAIL_DELAY_MS);
            } catch (InterruptedException ex) {
                // G.CON.10：mock 不重设中断标记，直接以 IOException 结束本次流
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
        if (query.contains("先答后退回")) {
            // 生产形态：控制器先流出部分业务答案（非结果节点的中间帧——不匹配
            // result-node-name、不命中转调识别，正常透传泄漏），随后才判定不在范围。
            // 泄漏帧与 not-in-scope 信封背靠背拼接进 L1 的 re-invoke 结果，
            // 验证信封在 L1 容错识别下仍胜出（而非依赖信封独占结果串）
            sseLines(out, partialAnswerEvent(invocation), notInScopeEvent(invocation));
            return;
        }
        if (query.contains("无结果节点")) {
            // 生产问题复刻（2026-08-25 抓样形态）：流内无 result-node-name 结果帧，
            // 业务内容在非结果节点帧正常透传，随后 End 结束节点 + workflow_end/end 收尾。
            // 基线提取为空、无转调信号——终态只能由 handler 的 onComplete 驱动；
            // 流式漏发终态事件会让上游误报 closed-before-terminal（TARGET_UNAVAILABLE）
            sseLines(out, passthroughAnswerEvent(invocation), nodeEndEvent(invocation),
                    prodWorkflowEndEvent(invocation), "{\"event\":\"end\"}");
            return;
        }
        if (query.contains("退回") || query.contains("循环")) {
            // 生产报文逐字复刻（2026-08-24 生产抓样）：带 index 的文本回显帧 → 完整信号帧
            // → node_end 帧 → workflow_end/end；命中后非信号帧全部被抑制
            sseLines(out, notInScopeEchoEvent(invocation), notInScopeEvent(invocation),
                    nodeEndEvent(invocation), prodWorkflowEndEvent(invocation), "{\"event\":\"end\"}");
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
                // G.CON.10：mock 不重设中断标记，记录后继续按场景回包
                log.warn("mock L2 timeout delay interrupted, answering immediately");
            }
        }
        sseLines(out, answerEvent("二级本域业务答案：机票已预订"), workflowEndEvent());
    }

    /**
     * 按查询关键字选择 L1 意图值。
     *
     * @param query 请求查询关键字
     * @return 命中的意图值；无转调场景返回 {@link Optional#empty()}
     */
    private static Optional<String> intentForQuery(String query) {
        if (query.contains("无目标")) {
            return Optional.of("99");
        }
        if (query.contains("越权")) {
            return Optional.of("6");
        }
        if (query.contains("不可达")) {
            return Optional.of("5");
        }
        if (query.contains("转调") || query.contains("退回") || query.contains("循环")
                || query.contains("补充信息") || query.contains("超时")) {
            return Optional.of("3");
        }
        return Optional.empty();
    }

    /**
     * L1 意图转调信号 — 生产报文样例原样（intent 值与 createdTime 随执行变化：
     * 真实控制器每条消息的 createdTime 不同，作为 dedup-key，重识别后的再次转调
     * 不会被判为重复消息）。
     *
     * @param intentId 意图值（如 "3"）
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 事件行
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
     *
     * @param intentId 意图值（回显到 data.text）
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 回显事件行
     */
    private static String echoMessageEvent(String intentId, int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"" + intentId + "\","
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"意图返回\","
                + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                + "\"createdTime\":" + (createdTime(invocation) - 1) + "}";
    }

    /**
     * L2 不在范围退回 — 文本回显帧，生产报文逐字复刻（2026-08-24 抓样）：带 index、
     * 无 summary，text 为「不在范围」；signal 类型旁路识别即命中。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 回显事件行
     */
    private static String notInScopeEchoEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"不在范围\",\"index\":0,"
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"Q\",\"node_name\":\"不在范围\","
                + "\"createdTime\":" + (createdTime(invocation) - 1) + ","
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * L2 不在范围退回信号 — 生产报文逐字复刻（2026-08-24 抓样，createdTime 随执行变化）。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 事件行
     */
    private static String notInScopeEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"不在范围\",\"index\":0,"
                + "\"node_id\":\"node_1787129452975\",\"node_type\":\"Q\",\"node_name\":\"不在范围\","
                + "\"is_finished\":true,\"createdTime\":" + createdTime(invocation) + ","
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * 结束节点消息帧 — 生产报文复刻（信号帧后到达，命中后整帧抑制）。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 事件行
     */
    private static String nodeEndEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"\","
                + "\"node_id\":\"node_end\",\"node_type\":\"End\",\"node_name\":\"结束\","
                + "\"is_finished\":true,\"createdTime\":" + (createdTime(invocation) + 7) + ","
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * 生产退回流的终态事件（workflow_end，answer 为空串；与本地答案路径的
     * workflow_finished 不同名，命中转调后到达即被抑制）。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return workflow_end SSE 事件行
     */
    private static String prodWorkflowEndEvent(int invocation) {
        return "{\"event\":\"workflow_end\",\"data\":{\"answer\":\"\",\"node_id\":\"node_end\","
                + "\"node_type\":\"End\",\"node_name\":\"结束\",\"is_finished\":true,"
                + "\"createdTime\":" + (createdTime(invocation) + 14) + ",\"should_interrupt\":false,"
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * 先答后退回的前置中间帧 — 生产报文复刻形态（message/Q/带 index 的文本帧）：
     * {@code node_name} 既非结果节点（不匹配 result-node-name {@code ABCDEResponseNode}，
     * 不被基线抽取拦截），也不命中转调识别字段值，因此流式正常透传泄漏，
     * 与随后到达的 not-in-scope 信封背靠背拼接为 L1 的远端结果串。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 事件行
     */
    private static String partialAnswerEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"前置业务输出：部分答案\",\"index\":0,"
                + "\"node_id\":\"node_1787129452976\",\"node_type\":\"Q\",\"node_name\":\"前置输出\","
                + "\"createdTime\":" + (createdTime(invocation) - 1) + ","
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * 无结果节点场景的业务内容帧 — 生产报文复刻形态（message/Q/带 index 的文本帧）：
     * {@code node_name} 不匹配 result-node-name（{@code ABCDEResponseNode}，不被基线
     * 抽取拦截），也不命中转调识别字段值，因此流式正常透传；终答即透传内容。
     *
     * @param invocation 会话内第几次调用（驱动 createdTime 变化）
     * @return message SSE 事件行
     */
    private static String passthroughAnswerEvent(int invocation) {
        return "{\"event\":\"message\",\"data\":{\"text\":\"无结果节点场景：流式直通业务内容\",\"index\":0,"
                + "\"node_id\":\"node_1787129452977\",\"node_type\":\"Q\",\"node_name\":\"业务输出\","
                + "\"createdTime\":" + (createdTime(invocation) - 1) + ","
                + "\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"workflow_name\":\"erjimorengongzuoliu_fenbushiyanzheng\"}}";
    }

    /**
     * 本地/本域业务答案。{@code data.text} 与 {@code outputs.response} 同值：前者供
     * FEAT-002 基线 result-node-name 提取（{@code extractLegacyText} 读 {@code data.text}），
     * 后者保持真实报文形态。{@code node_name} 与生产 result-node-name 配置
     * （{@code ABCDEResponseNode}）一致，答案帧必须命中才能被抽取。
     *
     * @param text 业务答案文本
     * @return node_finished SSE 事件行
     */
    private static String answerEvent(String text) {
        return "{\"event\":\"node_finished\",\"data\":{\"agent_id\":\"81476c36-28e6-4ec1-84c5-247be51a9327\","
                + "\"node_id\":\"node_answer\",\"node_status\":\"node_finished\",\"parent_workflow_id\":\"\","
                + "\"status\":{\"code\":0,\"desc\":\"succeeded\"},"
                + "\"node_name\":\"ABCDEResponseNode\",\"node_type\":\"QA\","
                + "\"inputs\":{},\"outputs\":{\"response\":\"" + text + "\"},\"text\":\"" + text + "\","
                + "\"start_time\":1787129556865,\"end_time\":1787129556867,"
                + "\"execution_id\":\"exec-answer\"},\"createdTime\":1787129556876}";
    }

    /**
     * 控制器真实异常（不满足转调识别条件，走 FEAT-002 错误映射）。
     *
     * @return exception SSE 事件行
     */
    private static String exceptionEvent() {
        return "{\"event\":\"exception\",\"data\":{\"code\":500,\"message\":\"workflow node failed\","
                + "\"node_id\":\"node_failed\"}}";
    }

    /**
     * L2 向最终用户请求补充信息（FEAT-002 基线中断信号，配置 interrupt.signal-match）。
     *
     * @return need_user_input SSE 事件行
     */
    private static String interruptEvent() {
        return "{\"event\":\"need_user_input\",\"data\":{\"question\":\"请补充入住日期\","
                + "\"input_schema\":\"date\",\"resume_token\":\"tok-123\"}}";
    }

    private static String workflowEndEvent() {
        return "{\"event\":\"workflow_finished\",\"data\":{\"node_id\":\"node_end\",\"node_type\":\"End\","
                + "\"node_status\":\"node_finished\"}}";
    }

    /**
     * 每次控制器执行生成新的 createdTime（真实控制器行为，作 dedup-key）。
     *
     * @param invocation 会话内第几次调用
     * @return 本执行的 createdTime
     */
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
        /**
         * Writes the canned scenario body.
         *
         * @param out response output stream
         * @throws IOException when writing fails
         */
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
