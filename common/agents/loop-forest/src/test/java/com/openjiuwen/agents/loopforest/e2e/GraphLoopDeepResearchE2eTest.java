/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.e2e;

import com.openjiuwen.agents.loopforest.search.WikipediaSearchTool;
import com.openjiuwen.agents.loopforest.rail.GraphLoopConfig;
import com.openjiuwen.agents.loopforest.rail.GraphLoopRails;
import com.openjiuwen.agents.edpa.subagent.SubAgentExecutor;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import com.openjiuwen.agents.loopforest.verification.VetoContract;
import com.openjiuwen.agents.loopforest.verification.VetoRail;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graph-loop Java 版 e2e——真实 LLM + 可切换搜索后端（Wikipedia/Zhipu）+ 多分支探索。
 *
 * <p>验证四层完整链路：搜索工具 → VetoRail 纪律 → fork 分支 → ConvergenceRail 收敛。
 *
 * <p>Env-gated：DEEPSEEK_API_KEY + DEEPSEEK_BASE_URL 存在时才跑。
 * 模型：deepseek-v4-flash（弱模型主力——GLH-2 定调）。
 *
 * <p>测试任务（BrowseComp 风格多跳）：
 * "The Eiffel Tower is in a country. What is that country's approximate population?
 *  Search for both facts and cite your sources."
 *
 * <p>期望行为：模型调用 web_search 多次（Eiffel Tower 位置 → 国家 → 人口），
 * 产出含数字的答案。fork_subtask 可能被调用也可能不（取决于模型是否分叉）。
 *
 * @since 2026-08
 */
class GraphLoopDeepResearchE2eTest {

    // 真正需要多跳+分叉：两条独立研究路径，需并行探索后综合
    private static final String TASK = "Compare the GDP of the countries where these two programming "
            + "languages were created:\n"
            + "1. Python (search for its creator, then their country)\n"
            + "2. Ruby (search for its creator, then their country)\n"
            + "\n"
            + "Use fork_subtask to research both in parallel (one fork per language), "
            + "then synthesize: which country has the higher GDP? "
            + "Cite your search sources. End with 'DONE' when complete.";

    private static boolean envPresent() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        return key != null && !key.isBlank() && base != null && !base.isBlank();
    }

    /** 拒绝消息真源（classpath 资源——与 veto e2e 同源，防占位串混入）。 */
    private static String vetoRejectionMessage() {
        try (java.io.InputStream in = VetoRail.class.getResourceAsStream(
                "/prompts/veto-rejection.txt")) {
            return in == null ? "write rejected"
                    : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "write rejected";
        }
    }

    @Test
    void deepResearchMultiHopWithGraphLoop() {
        Assumptions.assumeTrue(envPresent(),
                "Requires DEEPSEEK_API_KEY + DEEPSEEK_BASE_URL");

        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        String model = System.getenv().getOrDefault("GLH_MODEL", "deepseek-v4-flash");

        // ── 构建 agent ──
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder()
                .clientId("graph-loop-e2e-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(model).temperature(0.3).maxTokens(4000).build();

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("graph-loop-dr").build());
        agent.setLlm(new ToolCallingEnforcingModel(cliCfg, reqCfg));
        // 多跳+分叉需要更多轮（SDK 默认 5——Python 实验证实 thinking 模型需 15）
        Object cfg = agent.getConfig();
        if (cfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(25);
        }

        // 注册搜索工具（两步——SubAgentDispatcher 模式）
        // 后端选择：ZHIPU_SEARCH_API_KEY 在 → 智谱真搜索（与强模型对照侧同后端）；
        // 不在 → 回退 Wikipedia opensearch（弱数据通道对照基线）
        com.openjiuwen.core.foundation.tool.Tool searchTool;
        String zhipuKey = System.getenv("ZHIPU_SEARCH_API_KEY");
        if (zhipuKey != null && !zhipuKey.isBlank()) {
            searchTool = new com.openjiuwen.agents.loopforest.search.ZhipuWebSearchTool(zhipuKey);
        } else {
            searchTool = new WikipediaSearchTool();
        }
        System.out.println("[e2e] search.backend="
                + (zhipuKey != null && !zhipuKey.isBlank() ? "zhipu" : "wikipedia"));
        agent.getAbilityManager().add(searchTool.getCard());
        com.openjiuwen.core.runner.Runner.resourceMgr()
                .addTool(searchTool, agent.getCard().getId());

        // 组装 graph-loop（Veto + Forest + Fork + Convergence）
        // fork = 真子 agent：每个分叉是独立的 ReActAgent（自带 web_search，自主多跳）
        SubAgentExecutor subAgentExecutor = (userInput, subGoal) -> {
            ReActAgent sub = new ReActAgent(AgentCard.builder()
                    .name("fork-subagent").build());
            sub.setLlm(new ToolCallingEnforcingModel(cliCfg, reqCfg));
            Object subCfg = sub.getConfig();
            if (subCfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig subReactCfg) {
                subReactCfg.configureMaxIterations(14);
            }
            sub.getAbilityManager().add(searchTool.getCard());
            com.openjiuwen.core.runner.Runner.resourceMgr()
                    .addTool(searchTool, sub.getCard().getId());
            Object subResult = sub.invoke(
                    "Research this sub-goal using web_search. Then STOP searching "
                            + "and write the key facts and figures found (include numbers):\n"
                            + subGoal, null);
            return String.valueOf(subResult);
        };

        ConvergenceEvaluator evaluator = new ConvergenceEvaluator() {
            @Override
            public Optional<Double> score(String branchId, String result) {
                // 简单评分：结果含数字且长度 > 50 → 合格
                // 注意不能用 String.matches(".*\\d.*")——matches 锚定全串且 . 不跨行，
                // 多行 markdown 里的数字会被漏判（实测两分支全被误拒）
                if (result != null && result.length() > 50
                        && result.chars().anyMatch(Character::isDigit)) {
                    return Optional.of((double) result.length());
                }
                return Optional.empty();
            }
        };

        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                new GraphLoopConfig(
                        new VetoContract(Map.of()), // 不启用 Veto（搜索任务无固定 schema）
                        vetoRejectionMessage(),
                        evaluator,
                        subAgentExecutor,
                        "Fork a sub-task to explore a different research direction"));

        // ── 运行 ──
        long start = System.currentTimeMillis();
        Object rawResult = agent.invoke(TASK, null);
        long elapsed = System.currentTimeMillis() - start;

        String resultText = String.valueOf(rawResult);

        // ── 观测输出（终态语义断言见下方；fork/convergence 为观察面）──
        System.out.println("[e2e] elapsed=" + elapsed + "ms");
        System.out.println("[e2e] result=" + resultText);
        System.out.println("[e2e] forest.size=" + summary.forest().size());
        System.out.println("[e2e] candidates=" + summary.orchestrator().allResults().size());
        System.out.println("[e2e] convergence.winner="
                + (summary.convergenceRail() != null
                        ? summary.convergenceRail().getWinningBranchId() : "n/a"));

        // Agent 应产出非空结果
        assertThat(resultText).as("agent should produce non-empty result").isNotEmpty();

        // Agent 应产出含数字的答案（GDP 比较必须含数字）
        assertThat(resultText).as("result should contain digits (GDP numbers)")
                .containsPattern("\\d");
        // Agent 应提到至少一个国家（Netherlands 或 Japan）
        assertThat(resultText.toLowerCase())
                .as("result should mention Netherlands or Japan")
                .containsPattern("(netherlands|japan)");

        // 耗时 sanity（5 分钟上限）
        assertThat(elapsed).as("should complete within 5 minutes").isLessThan(300_000);
    }
}
