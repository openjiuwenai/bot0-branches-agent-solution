/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ProactiveConvergenceRail real-LLM e2e — 数据通道证明（rail 注册 + invoke 端到端跑通）。
 *
 * <p><b>状态隔离后诚实边界</b>：RailInvocationState per-invocation 后，invoke 返回 ctx 不可达，
 * post-invoke rail 内部状态（triggerCount/coverageHistory/toolRoundCount）读不到。所以本 e2e
 * <b>不再断言 rail 内部行为</b>（divergence-capture / 双向响应 wiring）——这些承重靠 mock 单测
 * {@code com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRailTest}（持有 ctx，可读状态）。
 * 本 e2e 只证 rail 注册到 ReActAgent + invoke 端到端跑通（数据通道 wiring），
 * 不证 rail 内部状态。
 *
 * <p>Env-gated opt-in: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} /
 * {@code EDPA_CONVERGENCE_E2E_ENABLED=true}.
 *
 * @since 2026-07
 */
class ProactiveConvergenceRealLlmE2eTest {
    private static final List<String> CRITERIA = List.of("营收 净利润", "毛利率 现金流", "营收增长");

    private static final String TASK = """
            你是财务分析师。请调用 fetch_finance 工具采集苹果公司的财务数据，分别查看营收和利润两方面
            （dimension 分别传 "营收" 和 "利润"），尽可能采集到写报告所需的维度数据，然后写一份简短财务分析报告。

            【规则】每次工具调用是单独一轮（不要在一轮里并行调多次）；最后一条无工具调用的回复即为最终报告。
            """;

    /**
     * Converging arm: tool returns real financials containing criteria keywords; asserts the rail is
     * registered to ReActAgent and invoke runs end-to-end producing non-empty output.
     *
     * @throws Exception if the underlying agent invoke or env-gated run fails
     */
    @Test
    @Timeout(300)
    void convergingArm_invokesWithoutError() throws Exception {
        String output = run("conv", true);
        assertThat(output)
                .as("converging arm: invoke must produce non-empty output (rail registered + data channel wiring)")
                .isNotEmpty();
    }

    /**
     * Diverging arm: tool returns real-but-off-topic data containing NO financial keyword; asserts the rail
     * is registered to ReActAgent and invoke runs end-to-end producing non-empty output.
     *
     * @throws Exception if the underlying agent invoke or env-gated run fails
     */
    @Test
    @Timeout(300)
    void divergingArm_invokesWithoutError() throws Exception {
        String output = run("div", false);
        assertThat(output)
                .as("diverging arm: invoke must produce non-empty output (rail registered + data channel wiring)")
                .isNotEmpty();
    }

    private String run(String role, boolean converge) throws Exception {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String enabled = System.getenv("EDPA_CONVERGENCE_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank() && "true".equalsIgnoreCase(enabled),
                "Convergence e2e requires OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL /"
                        + " EDPA_CONVERGENCE_E2E_ENABLED=true");

        String modelName = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        DefaultModelClientFactories.ensureRegistered();

        var cliCfg = ModelClientConfig.builder().clientId("edpa-conv-" + role + "-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(base).verifySsl(false).timeout(120000).build();
        var reqCfg = ModelRequestConfig.builder().modelName(modelName).temperature(0.3).topP(0.9).maxTokens(800)
                .build();
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-conv-" + role).build());
        agent.setLlm(model);
        Object cfg = agent.getConfig();
        if (cfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(15);
        }

        registerStub(agent, converge);

        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(new RuleBasedCriteriaVerifier(), CRITERIA, 2,
                ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL);
        agent.registerRail(rail);
        ReplanRail sharedCounter = new ReplanRail(3);
        agent.registerRail(new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), CRITERIA, sharedCounter));
        agent.registerRail(new ReplanRail(3));
        ReplanTool.registerOnto(agent);

        Object result = agent.invoke(TASK, null);
        // 状态隔离后 rail 内部状态（triggerCount/coverage）invoke 后不可读（ctx 不可达）；
        // 承重靠 mock（ProactiveConvergenceRailTest 持有 ctx）。本 e2e 只证数据通道 wiring。
        return String.valueOf(result);
    }

    private static void registerStub(ReActAgent agent, boolean converge) {
        Map<String, Object> dimParam = Map.of("type", "string", "description", "数据维度：营收 或 利润");
        ToolCard card = ToolCard.builder().id("fetch_finance").name("fetch_finance")
                .description("采集苹果公司财务数据。参数：dimension（'营收' 或 '利润'）。")
                .inputParams(Map.of("type", "object", "properties", Map.of("dimension", dimParam), "required",
                        List.of("dimension")))
                .build();
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                String dim = args != null ? String.valueOf(args.getOrDefault("dimension", "")) : "";
                return converge ? convergingData(dim) : divergingData(dim);
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    /**
     * Converging arm: real financials containing criteria keywords.
     *
     * @param dim the data dimension requested by the model ("营收" or "利润")
     * @return financial text containing criteria keywords for the given dimension
     */
    private static String convergingData(String dim) {
        if (dim.contains("利润")) {
            return "苹果最新财报：毛利率 46%，经营现金流 1100 亿美元，现金流状况稳健。";
        }
        return "苹果最新财报：营收 3910 亿美元，净利润 970 亿美元，营收增长 2%。";
    }

    /**
     * Diverging arm: real-but-off-topic company background containing NO financial keyword.
     *
     * @param dim the data dimension requested by the model ("营收" or "利润")
     * @return off-topic company-background text containing no criteria keyword
     */
    private static String divergingData(String dim) {
        if (dim.contains("利润")) {
            return "苹果公司现任 CEO 是 Tim Cook，公司 1980 年上市，"
                    + "股票代码 AAPL，总部位于加州。";
        }
        return "苹果公司 1976 年创立于加州库比蒂诺，总部 Apple Park 园区，"
                + "全球员工约 16.4 万人。";
    }
}
