/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
     * 会话与工具配置（聚合以控制 Query 参数数量，G.MET.01）。
     *
     * @param group        所属分组
     * @param strategy     会话策略
     * @param agentId      可选 agent 标识
     * @param exposure     可选工具暴露策略
     * @param mode         "streaming" 走 STREAMING；"blocking" 走 BLOCKING（仅 s2 用以验证 UNSUPPORTED_MODE）
     */
    record QueryConfig(
            Group group,
            ConversationStrategy strategy,
            Optional<String> agentId,
            Optional<ToolExposurePolicy> exposure,
            String mode) {
    }

    /**
     * 预期与说明（聚合以控制 Query 参数数量，G.MET.01）。
     *
     * @param expectedFailed    true 表示本 query 预期走到 FAILED 终态（s6）；否则预期 COMPLETED
     * @param expectedErrorCode 预期错误码
     * @param description       场景说明
     */
    record Expectation(
            boolean expectedFailed,
            Optional<String> expectedErrorCode,
            String description) {
    }

    /**
     * 一条验证 query。字段已聚合到 {@link QueryConfig} 与 {@link Expectation}，
     * 通过便捷方法暴露原字段以保持调用方简洁。
     */
    record Query(
            String id,
            String displayName,
            String input,
            QueryConfig config,
            Expectation expectation) {
        /**
         * 所属分组。
         *
         * @return 所属分组。
         */
        public Group group() {
            return config.group();
        }

        /**
         * 会话策略。
         *
         * @return 会话策略。
         */
        public ConversationStrategy conversationStrategy() {
            return config.strategy();
        }

        /**
         * 可选 agent 标识。
         *
         * @return 可选 agent 标识。
         */
        public Optional<String> agentId() {
            return config.agentId();
        }

        /**
         * 可选工具暴露策略。
         *
         * @return 可选工具暴露策略。
         */
        public Optional<ToolExposurePolicy> exposure() {
            return config.exposure();
        }

        /**
         * 调用模式。
         *
         * @return 调用模式。
         */
        public String mode() {
            return config.mode();
        }

        /**
         * 是否预期失败。
         *
         * @return 是否预期失败。
         */
        public boolean expectedFailed() {
            return expectation.expectedFailed();
        }

        /**
         * 预期错误码。
         *
         * @return 预期错误码。
         */
        public Optional<String> expectedErrorCode() {
            return expectation.expectedErrorCode();
        }

        /**
         * 场景说明。
         *
         * @return 场景说明。
         */
        public String description() {
            return expectation.description();
        }
    }

    /**
     * 返回全部条目。
     *
     * @return 返回全部条目
     */

    static List<Query> all() {
        List<Query> all = new java.util.ArrayList<>();
        all.addAll(serialQueries());
        all.addAll(soloQueries());
        all.addAll(demoQueries());
        return List.copyOf(all);
    }

    /**
     * serialQueries。
     *
     * @return serialQueries
     */

    private static List<Query> serialQueries() {
        return List.of(
                // ---- 串行组：共享 conv-serial-main，能连贯走通 ----
                new Query(
                        "s1", "S1 · STREAMING + 本地工具多轮",
                        "please read the page then submit the order",
                        new QueryConfig(Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.allow(DemoTools.READ_PAGE, DemoTools.SUBMIT_ORDER)),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "SSE 路径；readPage 与 submitOrder 各执行一次；ACTION 工具触发一次审批")),
                new Query(
                        "s2", "S2 · STREAMING ping",
                        "run ping",
                        new QueryConfig(Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.allow(DemoTools.PING)),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "BLOCKING 已被 SDK 拒绝（UNSUPPORTED_MODE），这里跑通 STREAMING ping")),
                new Query(
                        "s4", "S4 · 普通多轮（无工具）",
                        "hello turn",
                        new QueryConfig(Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                                Optional.empty(),
                                Optional.of(ToolExposurePolicy.none()),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "复用同一 conversationId 再 invoke，得到新 Task；不触发任何本地工具"
                                        + "（串行组里显式收窄为 none，避免继承 s1/s2 的会话级暴露）")),
                new Query(
                        "s5", "S5 · 默认不暴露",
                        "please read the page and submit the order",
                        new QueryConfig(Group.SERIAL, ConversationStrategy.REUSE_SERIAL,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.none()),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "显式声明 none → ToolView 为空 → 服务端不可见任何本地工具，直接 COMPLETED")));
    }

    /**
     * soloQueries。
     *
     * @return soloQueries
     */

    private static List<Query> soloQueries() {
        return List.of(
                // ---- 单独组：各自独立 conversationId ----
                new Query(
                        "s3", "S3 · 用户输入续传",
                        "NEEDS_USER_INPUT: what is your name?",
                        new QueryConfig(Group.SOLO, ConversationStrategy.FRESH,
                                Optional.empty(),
                                Optional.empty(),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "INPUT_REQUIRED → continueInput(\"Alice\") → COMPLETED（异步续传，单独会话）")),
                new Query(
                        "s6", "S6 · 治理错误（401 不投影为成功）",
                        "should be rejected by gateway",
                        new QueryConfig(Group.SOLO, ConversationStrategy.FRESH,
                                Optional.empty(),
                                Optional.empty(),
                                "streaming"),
                        new Expectation(true, Optional.of("transport_error"),
                                "无凭证 client → 网关 401 AUTH_MISSING → 以 FAILED 终态暴露（预期失败路径）")));
    }

    /**
     * demoQueries。
     *
     * @return demoQueries
     */

    private static List<Query> demoQueries() {
        return List.of(
                // ---- 口语化 demo：独立会话，自然语言驱动 ----
                new Query(
                        "d1", "Demo · 帮我读一下首页",
                        "帮我读一下首页",
                        new QueryConfig(Group.DEMO, ConversationStrategy.FRESH,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.allow(DemoTools.READ_PAGE)),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "口语化触发 readPage 观察类工具")),
                new Query(
                        "d2", "Demo · 提交订单 12345",
                        "帮我提交订单 12345",
                        new QueryConfig(Group.DEMO, ConversationStrategy.FRESH,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.allow(DemoTools.SUBMIT_ORDER)),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "口语化触发 submitOrder 动作类工具（需审批）")),
                new Query(
                        "d3", "Demo · ping 一下",
                        "ping 一下看看通不通",
                        new QueryConfig(Group.DEMO, ConversationStrategy.FRESH,
                                Optional.of("agent-x"),
                                Optional.of(ToolExposurePolicy.allow(DemoTools.PING)),
                                "streaming"),
                        new Expectation(false, Optional.empty(),
                                "口语化触发 ping 工具")));
    }

    /**
     * 按 id 查找。
     *
     * @param id String
     * @return 按 id 查找
     */

    static Query find(String id) {
        for (Query q : all()) {
            if (q.id().equals(id)) {
                return q;
            }
        }
        throw new IllegalArgumentException("unknown query: " + id);
    }
}
