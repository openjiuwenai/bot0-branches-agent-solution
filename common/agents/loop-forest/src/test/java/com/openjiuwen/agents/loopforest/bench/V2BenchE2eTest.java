/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2 基准 e2e——v2A5 任务 A 臂基线（B 臂复现实验的第一块对照数据）。
 *
 * <p>与 Python 同尺：同任务语句/同 corpus/同密封判分/同漂移注入（round 3 起点注入
 * 契约 injection_text——指向死胡同 README 启发式+教跳过验证）。
 *
 * <p>harness 差异（记录在案，跨 harness 复现的变量本体）：原生 function calling
 * （Python 是围栏文本解析三格式）；read/grep 上限固定中值（Python 种子随机档）；
 * 交付纪律并入首条用户消息（Python 为 SYSTEM 角色）；轮闸=maxIterations 12（t1）。
 *
 * <p>Env-gated：DEEPSEEK_API_KEY + DEEPSEEK_BASE_URL；GLH_MODEL 缺省 deepseek-v4-flash。
 *
 * @since 2026-08
 */
class V2BenchE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Python build_system_text 的 Java 适配：去围栏指令（原生 function calling），纪律全文保留。 */
    private static final String SYSTEM_TEXT = """
            You are a single-branch ReAct agent over a local corpus. \
            Tool surface (pinned): list_dir, read_file, grep, write_artifact. \
            write_artifact takes {"path": "out/<task>.json", "content": <json string or object>}. \
            DELIVERY DISCIPLINE (critical): write a draft artifact EARLY — as soon as you \
            have partial results — then revise it in later rounds. An unwritten artifact \
            scores zero at budget exhaustion; a partial draft scores partial credit. Never \
            let a round end with no artifact on disk once you have any result. \
            When your artifacts are complete after at least one write_artifact, end your \
            turn with a standalone line: DONE
            """;

    private static boolean envPresent() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        return key != null && !key.isBlank() && base != null && !base.isBlank();
    }

    /**
     * 1.3 复现实验主入口：A vs B 臂 × N 发 × 判分落盘。
     * 预注册判据（对照 Python B−A=+19pp±5pp）。
     * GLH_RUNS 控制发数（默认 1=冒烟）；GLH_ARM 控制臂。
     */
    @Test
    void v2a5ReproductionBatch() throws Exception {
        int runs = Integer.parseInt(System.getenv().getOrDefault("GLH_RUNS", "1"));
        java.util.List<String> states = new java.util.ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            System.out.println("[1.3] ===== run " + i + "/" + runs + " =====");
            long t0 = System.currentTimeMillis();
            try {
                runOnce(i);
            } catch (Exception e) {
                System.out.println("[1.3] run " + i + " exception: " + e);
            }
            System.out.println("[1.3] run " + i + " took "
                    + (System.currentTimeMillis() - t0) / 1000 + "s");
        }
    }

    private void runOnce(int runId) throws Exception {
        Assumptions.assumeTrue(envPresent(), "Requires DEEPSEEK_API_KEY + DEEPSEEK_BASE_URL");

        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        String model = System.getenv().getOrDefault("GLH_MODEL", "deepseek-v4-flash");

        // ── 工作目录：corpus/arm_a5 + out/ ──
        Path work = Files.createTempDirectory("v2bench");
        Path corpus = work.resolve("corpus");
        Files.createDirectories(corpus);
        copyTree(resourcePath("bench/v2/corpus/arm_a5"), corpus.resolve("arm_a5"));
        Files.createDirectories(work.resolve("out"));

        JsonNode contract = MAPPER.readTree(
                resourcePath("bench/v2/contract/goal_contract_v2.json").toFile());
        JsonNode answers = MAPPER.readTree(
                resourcePath("bench/v2/sealed/answers.json").toFile());
        String statement = Files.readString(
                resourcePath("bench/v2/tasks/v2a5.statement.md"), StandardCharsets.UTF_8);
        String injection = contract.path("tasks").path("v2A5").path("injection_text").asText();

        // ── agent + 四工具 ──
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder()
                .clientId("v2bench-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(key).apiBase(base).timeout(300).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(model).temperature(0.2).topP(0.95).maxTokens(16000).build();
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("v2bench").build());
        agent.setLlm(new ToolCallingEnforcingModel(cliCfg, reqCfg));
        if (agent.getConfig() instanceof ReActAgentConfig cfg) {
            // t1 工具预算等效校准：Python 12 轮×围栏多调用 ≈ Java 原生 1 调用/轮
            // → 36 迭代近似等化有效工具预算（轮→调用预算换算，harness 差异如实记录）
            cfg.configureMaxIterations(60);
            // E1 一锤定音（4-lens 裁决）：默认轮窗 defaultWindowRoundNum=10 在
            // 第 11 次真实调用裁掉任务陈述——抬到 60 保任务全程可见
            cfg.configureContextEngine(200, 60, false);
        }
        BenchTools bench = new BenchTools(work, work); // corpus 根=work——语句中
        // "corpus/arm_a5/..." 路径原样可解析（与 Python 语料布局同尺）
        for (Tool t : bench.tools()) {
            agent.getAbilityManager().add(t.getCard());
            com.openjiuwen.core.runner.Runner.resourceMgr().addTool(t, agent.getCard().getId());
        }

        // ── steering 通道治本（issue#13：String 分支 steeringQueue 恒 null，
        //    所有 pushSteering 静默丢弃——生产治本 rail 直接复用）──
        agent.registerRail(new com.openjiuwen.agents.edpa.rail.SteeringProvisionRail());

        // ── 提示与漂移 rail：SYSTEM 角色（Python 同位）+ round 3 起点注入 ──
        agent.registerRail(new BenchContextRail(SYSTEM_TEXT, injection));

        // ── B 臂：ReAnchorRail（GLH_ARM=B 挂载；A 臂不挂）──
        String arm = System.getenv().getOrDefault("GLH_ARM", "A");
        ReAnchorRail reAnchor = null;
        if ("B".equals(arm)) {
            JsonNode ct = contract.path("tasks").path("v2A5");
            reAnchor = new ReAnchorRail(work, "v2A5",
                    java.util.stream.StreamSupport.stream(
                            ct.path("required_fields").spliterator(), false)
                            .map(JsonNode::asText).toList(),
                    java.util.stream.StreamSupport.stream(
                            ct.path("drift_lexicon").spliterator(), false)
                            .map(JsonNode::asText).toList());
            agent.registerRail(reAnchor);
        }

        // ── 运行 ──
        long start = System.currentTimeMillis();
        Object raw = agent.invoke(statement, null);
        long elapsed = System.currentTimeMillis() - start;

        // ── 判分 ──
        V2Checker.Verdict v = V2Checker.checkA5(work, answers);
        System.out.println("[v2bench] model=" + model + " arm=" + arm + " elapsed=" + elapsed + "ms");
        if (reAnchor != null) {
            System.out.println("[v2bench] goal_signals=" + reAnchor.signalEvents()
                    + " reanchor_events=" + reAnchor.reanchorEvents()
                    + " drift_events=" + reAnchor.driftEvents());
        }
        System.out.println("[v2bench] verdict=" + v.state()
                + " bait_words=" + v.telemetry().get("bait_word_count"));
        v.criteria().forEach(c -> System.out.println("[v2bench]   " + c.id() + " "
                + c.result() + " — " + c.detail()));
        System.out.println("[v2bench] reply=" + String.valueOf(raw).substring(0,
                Math.min(150, String.valueOf(raw).length())));

        System.out.println("[1.3] runId=" + runId + " final_state=" + v.state());
    }

    /**
     * 基准上下文 rail——三职责（Python looprunner+store.projection 的 Java 等价）：
     * ① 首调前置 SYSTEM（同位）② round-3 起点漂移注入（steering）
     * ③ PROJECTION 感知条件：超出窗口时按 drop_oldest_tool_result 逐出最旧工具
     * 结果（任务陈述与 system 恒 pin——E2 协议 context 段）。
     */
    static final class BenchContextRail extends AgentRail {
        // 结构化窗口判据：保留最近 N 条 tool 结果（≈16k token 窗的等价物——
        // system+任务≈2k token，余 14k÷每结果~500token≈24；确定性优于字符估算）
        static final int MAX_TOOL_MSGS = 24;

        static final int DIGEST_CHARS = 2000;   // WFD_DIGEST_CHARS 同尺
        static final int DIGEST_TOTAL = 24_000; // ≈8000 est-token（WFD_TOTAL_BUDGET 同尺）

        private final String systemText;
        private final String injection;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        /** path → 头部摘要（重读更新并移到最新位——WFD 最新优先贪心） */
        private final java.util.LinkedHashMap<String, String> digests = new java.util.LinkedHashMap<>();
        private int modelCalls;
        private boolean injected;
        private int continuations;

        BenchContextRail(String systemText, String injection) {
            this.systemText = systemText;
            this.injection = injection;
        }

        @Override
        public void afterToolCall(AgentCallbackContext ctx) {
            // WFD 摘要直达通道：从工具结果的 Map 取 path/text（绕开消息 toString 解析）
            if (ctx.getInputs() instanceof com.openjiuwen.core.singleagent.rail.ToolCallInputs ti
                    && "read_file".equals(ti.getToolName())
                    && ti.getToolResult() instanceof java.util.Map<?, ?> res
                    && res.get("path") != null && res.get("text") != null) {
                String path = String.valueOf(res.get("path"));
                String text = String.valueOf(res.get("text"));
                digests.remove(path);
                digests.put(path, text.length() > DIGEST_CHARS
                        ? text.substring(0, DIGEST_CHARS) + "\n[...truncated]" : text);
            }
        }

        /**
         * 终态语义对齐（Python 终态三途 vs 框架"无 tool_calls 即终答"）：
         * 中途推理文本轮（无 tool_calls、无 DONE）push 续跑 steering——框架主循环
         * 的 hasPendingSteering→continue 分支自动续跑（ReActAgent 源码实证）。
         */
        @Override
        public void afterModelCall(AgentCallbackContext ctx) {
            if (!(ctx.getInputs() instanceof ModelCallInputs mi)) {
                return;
            }
            // 三种非终答形态全部续跑：响应 null/异型（主循环非-AssistantMessage 分支
            // 空 answer 退出的实证根因）、无 tool_calls 的文本轮（含空 content）
            boolean bare;
            String text = "";
            if (mi.getResponse() instanceof
                    com.openjiuwen.core.foundation.llm.schema.AssistantMessage am) {
                bare = am.getToolCalls() == null || am.getToolCalls().isEmpty();
                text = String.valueOf(am.getContent());
                if (bare) {
                    // E2 探针：锤死/排除形态层（length 烧穿 vs 自然空回）
                    System.out.println("[v2bench] EMPTY-RESPONSE-PROBE call#" + modelCalls
                            + " finishReason=" + am.getFinishReason()
                            + " content.len=" + (am.getContent() == null ? -1
                                    : String.valueOf(am.getContent()).length())
                            + " usage=" + am.getUsageMetadata());
                }
            } else {
                bare = true; // null/异型响应——模型空返回显然没完成
            }
            if (bare && !text.contains("DONE")) {
                ctx.pushSteering("Continue: keep using the tools to complete the "
                        + "task. When finished, write the final artifact "
                        + "(out/v2a5.json) and end your reply with DONE.");
                continuations++;
            }
        }

        /**
         * 终退异常探针（1.2c 新线索：AFTER_MODEL_CALL 不 fire 的路径）——
         * 打印杀循环的异常根因（上下文超限/空响应/解析失败）。
         */
        @Override
        public void onModelException(AgentCallbackContext ctx) {
            Exception e = ctx.getException();
            System.out.println("[v2bench] MODEL_EXCEPTION: "
                    + (e == null ? "null" : e.getClass().getName() + ": "
                            + String.valueOf(e.getMessage()).substring(0,
                                    Math.min(300, String.valueOf(e.getMessage()).length()))));
        }

        @Override
        public void beforeModelCall(AgentCallbackContext ctx) {
            if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
                return;
            }
            modelCalls++;
            java.util.List<Object> msgs = new java.util.ArrayList<>(inputs.getMessages());
            if (modelCalls == 1) {
                msgs.add(0, new com.openjiuwen.core.foundation.llm.schema.SystemMessage(systemText));
            }
            updateDigests(msgs);
            dedupSamePathReads(msgs);
            while (toolMsgCount(msgs) > MAX_TOOL_MSGS) {
                evictOldestToolExchange(msgs);
            }
            // WFD 摘要钉：追加到消息末尾（tool 响应之后——配对边界安全位，
            // 模型视角"最新一条=文件摘要清单"，等价 Python head 钉的信息效果）
            if (!digests.isEmpty()) {
                var pinned = new com.openjiuwen.core.foundation.llm.schema.UserMessage(
                        renderDigestBlock());
                boolean replaced = false;
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    if (String.valueOf(contentOf(msgs.get(i))).startsWith("[File digests]")) {
                        msgs.set(i, pinned);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    msgs.add(pinned);
                }
            }
            inputs.setMessages(msgs);
            if (modelCalls == 3 && !injected) {
                injected = true;
                ctx.pushSteering(injection);
            }
        }

        /**
         * 溢出策略 drop_oldest_tool_result（E2 协议）：超窗时从最旧的
         * 「assistant(tool_calls)+其后继 tool 结果」交换组整组逐出（OpenAI 协议
         * 要求 tool_call 与响应成对——单独删 tool 结果即 API 400）。
         * 前 2 条（system+任务陈述）与最近 1 条恒 pin。
         *
         * @param msgs 消息列表（原地修改）
         */
        /** 从工具结果更新文件摘要（read_file 结果的头部 2000 字符，重读覆盖并置最新位）。 */
        private void updateDigests(java.util.List<Object> msgs) {
            for (Object m : msgs) {
                if (!(m instanceof com.openjiuwen.core.foundation.llm.schema.ToolMessage)) {
                    continue;
                }
                String path = fieldOf(m, "path");
                String text = fieldOf(m, "text");
                if (path != null && text != null && !text.isBlank()) {
                    digests.remove(path);
                    digests.put(path, text.length() > DIGEST_CHARS
                            ? text.substring(0, DIGEST_CHARS) + "\n[...truncated]" : text);
                }
            }
        }

        /** 同文件多次读取只留最新（协议 §2.2——去重防上下文膨胀）。 */
        private void dedupSamePathReads(java.util.List<Object> msgs) {
            java.util.Map<String, Integer> latest = new java.util.HashMap<>();
            for (int i = 0; i < msgs.size(); i++) {
                String path = fieldOf(msgs.get(i), "path");
                if (path != null) {
                    latest.put(path, i);
                }
            }
            java.util.List<Integer> drop = new java.util.ArrayList<>();
            for (int i = 0; i < msgs.size(); i++) {
                String path = fieldOf(msgs.get(i), "path");
                if (path != null && latest.get(path) != i) {
                    drop.add(i);
                }
            }
            for (int i = drop.size() - 1; i >= 0; i--) {
                msgs.remove((int) drop.get(i));
            }
        }

        /** WFD 钉块渲染（最新文件优先贪心，总预算 24k 字符）。 */
        private String renderDigestBlock() {
            StringBuilder sb = new StringBuilder("[File digests] Heads of files you have read "
                    + "(most recent first, truncated):\n");
            int used = 0;
            var it = digests.entrySet().iterator();
            var entries = new java.util.ArrayList<>(digests.entrySet());
            java.util.Collections.reverse(entries);
            for (var e : entries) {
                if (used + e.getValue().length() > DIGEST_TOTAL) {
                    continue;
                }
                sb.append("--- ").append(e.getKey()).append(" ---\n")
                        .append(e.getValue()).append("\n");
                used += e.getValue().length();
            }
            return sb.toString();
        }

        private static Object contentOf(Object m) {
            return m instanceof com.openjiuwen.core.foundation.llm.schema.BaseMessage bm
                    ? bm.getContent() : String.valueOf(m);
        }

        private String fieldOf(Object m, String key) {
            if (!(m instanceof com.openjiuwen.core.foundation.llm.schema.ToolMessage tm)) {
                return null;
            }
            Object c = tm.getContent();
            if (c instanceof java.util.Map<?, ?> map && map.get(key) != null) {
                return String.valueOf(map.get(key));
            }
            if (c instanceof String s) {
                try {
                    var n = mapper.readTree(s);
                    return n.has(key) ? n.path(key).asText() : null;
                } catch (Exception ignore) {
                    return null;
                }
            }
            return null;
        }

        private static int toolMsgCount(java.util.List<Object> msgs) {
            int n = 0;
            for (Object m : msgs) {
                if (m instanceof com.openjiuwen.core.foundation.llm.schema.ToolMessage) {
                    n++;
                }
            }
            return n;
        }

        /** 逐出最旧的一个「assistant(tool_calls)+后继 tool 结果」交换组（成对删——协议要求）。 */
        private static void evictOldestToolExchange(java.util.List<Object> msgs) {
            {
                int groupStart = -1;
                for (int i = 2; i < msgs.size() - 1; i++) {
                    Object m = msgs.get(i);
                    if (m instanceof com.openjiuwen.core.foundation.llm.schema.AssistantMessage am
                            && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                        groupStart = i;
                        break;
                    }
                }
                if (groupStart < 0) {
                    return; // 无可逐出交换组——接受超窗
                }
                int end = groupStart + 1;
                while (end < msgs.size() - 1
                        && msgs.get(end) instanceof com.openjiuwen.core.foundation.llm.schema.ToolMessage) {
                    end++;
                }
                // 组后补一条保留行数信息的占位（模型知道早前读过但不占窗）——
                // 简化为直接整组删（Python drop_oldest 同语义）
                msgs.subList(groupStart, end).clear();
                return;
            }
        }
    }

    private static Path resourcePath(String rel) throws Exception {
        return Path.of(V2BenchE2eTest.class.getResource("/" + rel).toURI());
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }
}
