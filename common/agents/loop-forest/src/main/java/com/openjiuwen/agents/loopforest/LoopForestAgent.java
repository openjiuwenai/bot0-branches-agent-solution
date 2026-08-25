/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest;

import com.openjiuwen.agents.edpa.rail.SteeringProvisionRail;
import com.openjiuwen.agents.edpa.subagent.SubAgentExecutor;
import com.openjiuwen.agents.loopforest.rail.BudgetRail;
import com.openjiuwen.agents.loopforest.rail.GraphLoopConfig;
import com.openjiuwen.agents.loopforest.rail.GraphLoopRails;
import com.openjiuwen.agents.loopforest.search.PromptTemplates;
import com.openjiuwen.agents.loopforest.search.WikipediaSearchTool;
import com.openjiuwen.agents.loopforest.search.ZhipuWebSearchTool;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import com.openjiuwen.agents.loopforest.verification.VetoContract;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.runner.Runner;

import java.util.Map;

/**
 * 循环森林统一入口——组合 {@link ReActAgent} 与全套外置纪律（一个 build() 得到完整能力）。
 *
 * <p>内置的"踩过的坑"（bench 调试实证，新宿主不再踩）：
 * <ul>
 *   <li>{@link SteeringProvisionRail} 必挂——String 分支 invoke 下
 *       pushSteering 静默失效（agent-core issue 材料，缺陷 #1）</li>
 *   <li>轮窗抬升——默认 defaultWindowRoundNum=10 在第 11 次调用裁掉任务陈述
 *       （configureContextEngine(200, 60, false)，4-lens 裁决实证的盲飞起点）</li>
 *   <li>思考模型 maxTokens 默认 16000——8000 烧穿实证（thinking token 计入配额）</li>
 * </ul>
 *
 * <p>最小用法：
 * <pre>
 * LoopForestAgent agent = LoopForestAgent.builder()
 *         .apiKey(key).apiBase(base).model("deepseek-v4-flash")
 *         .build();
 * Object result = agent.invoke(task);
 * </pre>
 *
 * <p>机器配重（1.3 复现实验结论）：弱地板模型（如 qwen3.7-flash）吃不到
 * 重锚收益——按模型档位选 {@link GoalMachine} 档（NONE 适合地板模型）。
 *
 * @since 2026-08
 */
public final class LoopForestAgent {

    private final ReActAgent host;
    private final GraphLoopRails.RegistrationSummary graph;
    private final BudgetRail budget;

    private LoopForestAgent(Builder b) {
        DefaultModelClientFactories.ensureRegistered();
        this.host = new ReActAgent(AgentCard.builder()
                .name(b.agentName).build());
        var cli = ModelClientConfig.builder()
                .clientId("loop-forest-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(b.apiKey).apiBase(b.apiBase).verifySsl(false)
                .timeout(b.timeoutSeconds)
                .build();
        var req = ModelRequestConfig.builder()
                .modelName(b.model)
                .temperature(b.temperature)
                .topP(0.95)
                .maxTokens(b.maxTokens)
                .build();
        host.setLlm(new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cli, req));
        if (host.getConfig() instanceof ReActAgentConfig cfg) {
            cfg.configureMaxIterations(b.maxIterations);
            cfg.configureContextEngine(200, 60, false);
        }
        // steering 治本必挂（String 分支缺陷 #1）
        host.registerRail(new SteeringProvisionRail());

        // 搜索后端（可选）
        if (b.searchApiKey != null) {
            var tool = new ZhipuWebSearchTool(b.searchApiKey);
            host.getAbilityManager().add(tool.getCard());
            Runner.resourceMgr().addTool(tool, host.getCard().getId());
        } else {
            var tool = new WikipediaSearchTool();
            host.getAbilityManager().add(tool.getCard());
            Runner.resourceMgr().addTool(tool, host.getCard().getId());
        }

        // graph 四层 + 机器配重
        SubAgentExecutor executor = b.executor != null ? b.executor : (in, goal) -> "sub-task not configured";
        VetoContract contract = b.contract != null ? b.contract : new VetoContract(Map.of());
        String rejection = b.rejectionMessage != null ? b.rejectionMessage : "write rejected";
        this.graph = GraphLoopRails.registerOnto(host, new GraphLoopConfig(
                contract, rejection, b.evaluator, executor,
                "Fork a sub-task to explore a different direction"));
        this.budget = b.budgetWidth > 0
                ? new BudgetRail(b.budgetWidth, b.budgetForks, b.budgetTokens)
                : null;
        if (budget != null) {
            host.registerRail(budget);
        }
    }

    /**
     * 委托宿主 invoke（String 分支——steering 已由内置治本 rail 保障）。
     *
     * @param task 任务文本
     * @return 终态结果
     */
    public Object invoke(String task) {
        return host.invoke(task, null);
    }

    /** 森林句柄（分支查询/树导航）。 */
    public GraphLoopRails.RegistrationSummary graph() {
        return graph;
    }

    /** 预算遥测（未配置返回 null）。 */
    public BudgetRail budget() {
        return budget;
    }

    /**
     * 构造器。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 目标机器配重档位（1.3 复现：地板模型选 NONE）。 */
    public enum GoalMachine { NONE, REANCHOR }

    /** Builder——必填 apiKey/apiBase/model，其余有默认。 */
    public static final class Builder {
        private String apiKey;
        private String apiBase;
        private String model;
        private String agentName = "loop-forest";
        private double temperature = 0.2;
        private int maxTokens = 16000;      // thinking 烧穿实证的默认
        private double timeoutSeconds = 300;
        private int maxIterations = 36;     // 工具预算校准值
        private String searchApiKey;        // null → Wikipedia 回退
        private SubAgentExecutor executor;
        private VetoContract contract;
        private ConvergenceEvaluator evaluator;
        private String rejectionMessage;
        private int budgetWidth;
        private int budgetForks = 5;
        private long budgetTokens;

        /** LLM API key（必填）。 */
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        /** LLM API base（必填）。 */
        public Builder apiBase(String v) { this.apiBase = v; return this; }
        /** 模型名（必填）。 */
        public Builder model(String v) { this.model = v; return this; }
        /** agent 卡名（默认 loop-forest）。 */
        public Builder agentName(String v) { this.agentName = v; return this; }
        /** 温度（默认 0.2）。 */
        public Builder temperature(double v) { this.temperature = v; return this; }
        /** 智谱搜索 key（不设 → Wikipedia 回退）。 */
        public Builder searchApiKey(String v) { this.searchApiKey = v; return this; }
        /** 分叉执行器（不设 → 占位）。 */
        public Builder executor(SubAgentExecutor v) { this.executor = v; return this; }
        /** 写入契约（不设 → 不启用 Veto）。 */
        public Builder contract(VetoContract v) { this.contract = v; return this; }
        /** 收敛评估器（不设 → 无终态归因）。 */
        public Builder evaluator(ConvergenceEvaluator v) { this.evaluator = v; return this; }
        /** 预算三维（width&gt;0 启用 BudgetRail）。 */
        public Builder budget(int width, int forks, long tokens) {
            this.budgetWidth = width; this.budgetForks = forks; this.budgetTokens = tokens;
            return this;
        }

        /**
         * 构建（一次性完成全部装配）。
         *
         * @return 就绪的 LoopForestAgent
         */
        public LoopForestAgent build() {
            if (apiKey == null || apiBase == null || model == null) {
                throw new IllegalStateException("apiKey/apiBase/model 必填");
            }
            return new LoopForestAgent(this);
        }
    }
}
