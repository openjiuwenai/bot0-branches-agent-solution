package com.huawei.ascend.client.verify;

import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;

import java.util.List;
import java.util.Optional;

/**
 * 可枚举的 query 目录（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>把 6 个断言场景 + 口语化 demo 固化为按钮，前端按分组渲染。
 * 每条 query 携带它的会话策略与断言，供 {@link ConversationDriver} 编排。
 *
 * <p>分组语义（已与用户确认）：
 * <ul>
 *   <li>{@link Group#SERIAL} —— 串行组，共享同一 {@code conversationId}，能连贯走通（s1/s2-stream/s4/s5）。</li>
 *   <li>{@link Group#SOLO} —— 单独组，各自独立 {@code conversationId}：s3（异步续传）、s6（预期 401 失败）。</li>
 *   <li>{@link Group#DEMO} —— 口语化 demo，独立会话，展示自然语言驱动的工具调用。</li>
 * </ul>
 */
final class QueryCatalog {

    /** 串行组共享的 conversationId（真·一个对话）。 */
    static final String SERIAL_CONVERSATION_ID = "conv-serial-main";

    enum Group {
        SERIAL("串行组（同一对话连续发送）"),
        SOLO("单独组（各自独立会话）"),
        DEMO("口语化 demo（独立会话）");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** 会话策略：串行复用既有 conversationId；单独/demo 各自新建。 */
    enum ConversationStrategy {
        REUSE_SERIAL,
        FRESH
    }

    /**
     * @param mode           "streaming" 走 STREAMING；"blocking" 走 BLOCKING（仅 s2 用以验证 UNSUPPORTED_MODE）
     * @param expectedFailed true 表示本 query 预期走到 FAILED 终态（s6）；否则预期 COMPLETED
     */
    record Query(
            String id,
            String displayName,
            String input,
            Group group,
            ConversationStrategy conversationStrategy,
            Optional<String> agentId,
            Optional<ToolExposurePolicy> exposure,
            String mode,
            boolean expectedFailed,
            Optional<String> expectedErrorCode,
            String description) {
    }

    static List<Query> all() {
        return List.of(
                // ---- 串行组：共享 conv-serial-main，能连贯走通 ----
                new Query(
                        "s1", "S1 · STREAMING + 本地工具多轮",
                        "please read the page then submit the order",
                        Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.allow(DemoTools.READ_PAGE, DemoTools.SUBMIT_ORDER)),
                        "streaming", false, Optional.empty(),
                        "SSE 路径；readPage 与 submitOrder 各执行一次；ACTION 工具触发一次审批"),
                new Query(
                        "s2", "S2 · STREAMING ping",
                        "run ping",
                        Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.allow(DemoTools.PING)),
                        "streaming", false, Optional.empty(),
                        "BLOCKING 已被 SDK 拒绝（UNSUPPORTED_MODE），这里跑通 STREAMING ping"),
                new Query(
                        "s4", "S4 · 普通多轮（无工具）",
                        "hello turn",
                        Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                        Optional.empty(),
                        Optional.of(ToolExposurePolicy.none()),
                        "streaming", false, Optional.empty(),
                        "复用同一 conversationId 再 invoke，得到新 Task；不触发任何本地工具"
                        + "（串行组里显式收窄为 none，避免继承 s1/s2 的会话级暴露）"),
                new Query(
                        "s5", "S5 · 默认不暴露",
                        "please read the page and submit the order",
                        Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.none()),
                        "streaming", false, Optional.empty(),
                        "显式声明 none → ToolView 为空 → 服务端不可见任何本地工具，直接 COMPLETED"),

                // ---- 单独组：各自独立 conversationId ----
                new Query(
                        "s3", "S3 · 用户输入续传",
                        "NEEDS_USER_INPUT: what is your name?",
                        Group.SOLO, ConversationStrategy.FRESH,
                        Optional.empty(),
                        Optional.empty(),
                        "streaming", false, Optional.empty(),
                        "INPUT_REQUIRED → continueInput(\"Alice\") → COMPLETED（异步续传，单独会话）"),
                new Query(
                        "s6", "S6 · 治理错误（401 不投影为成功）",
                        "should be rejected by gateway",
                        Group.SOLO, ConversationStrategy.FRESH,
                        Optional.empty(),
                        Optional.empty(),
                        "streaming", true, Optional.of("transport_error"),
                        "无凭证 client → 网关 401 AUTH_MISSING → 以 FAILED 终态暴露（预期失败路径）"),

                // ---- 口语化 demo：独立会话，自然语言驱动 ----
                new Query(
                        "d1", "Demo · 帮我读一下首页",
                        "帮我读一下首页",
                        Group.DEMO, ConversationStrategy.FRESH,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.allow(DemoTools.READ_PAGE)),
                        "streaming", false, Optional.empty(),
                        "口语化触发 readPage 观察类工具"),
                new Query(
                        "d2", "Demo · 提交订单 12345",
                        "帮我提交订单 12345",
                        Group.DEMO, ConversationStrategy.FRESH,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.allow(DemoTools.SUBMIT_ORDER)),
                        "streaming", false, Optional.empty(),
                        "口语化触发 submitOrder 动作类工具（需审批）"),
                new Query(
                        "d3", "Demo · ping 一下",
                        "ping 一下看看通不通",
                        Group.DEMO, ConversationStrategy.FRESH,
                        Optional.of("agent-x"),
                        Optional.of(ToolExposurePolicy.allow(DemoTools.PING)),
                        "streaming", false, Optional.empty(),
                        "口语化触发 ping 工具")
        );
    }

    static Query find(String id) {
        for (Query q : all()) {
            if (q.id().equals(id)) {
                return q;
            }
        }
        throw new IllegalArgumentException("unknown query: " + id);
    }
}
