/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM e2e: RootCauseRail on real ReActAgent + real GLM.
 *
 * <p>Tests the device-failure degrade channel: a tool that throws on first call →
 * RootCauseRail.onToolException marks → next afterModelCall → forceFinish(degraded).
 * The LLM drives the ReActAgent loop; when the tool fails, the rail degrades honestly.
 */
class RootCauseRailRealLlmE2eTest {

    @Test
    void realLlm_toolFailure_rootCauseRailDegrades() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(),
                "OPENJIUWEN_API_KEY 未设置，跳过真 LLM e2e");

        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder()
                .clientId("rootcause-e2e-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(System.getenv("OPENJIUWEN_API_KEY"))
                .apiBase(System.getenv("OPENJIUWEN_BASE_URL"))
                .verifySsl(false).build();
        String effectiveModel = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-pro");
        var reqCfg = ModelRequestConfig.builder()
                .modelName(effectiveModel)
                .temperature(0.3).maxTokens(200).build();
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("e2e-rootcause").build());
        agent.setLlm(model);

        // Register a tool that ALWAYS throws → triggers onToolException.
        // Two-step registration: abilityManager.add(card) for LLM visibility (listToolInfo)
        // and Runner.resourceMgr().addTool(tool, null) so execute→getToolFromResourceMgr can
        // resolve & invoke it. abilityManager.add(tool) silently drops Tool instances
        // (addSingle only accepts ToolCard/WorkflowCard/AgentCard/McpServerConfig).
        AlwaysFailTool failTool = new AlwaysFailTool();
        agent.getAbilityManager().add(failTool.getCard());
        Runner.resourceMgr().addTool(failTool, null);

        // Register RootCauseRail
        agent.registerRail(new RootCauseRail());

        Object result = agent.invoke("使用 fetchData 工具获取数据，然后分析。", null);

        // 基线断言（始终执行）：agent 跑完返回非 null，证明 e2e infra 健康（真 LLM →
        // ReActAgent → 工具执行 → rail 链路畅通）。这不是承重断言——通道装配的硬证明由
        // RootCauseRailTest（mock）承担；此处只证 e2e 跑通，避免 LLM 不走预期路径时无断言。
        assertThat(result).as("agent 必须返回结果（e2e infra 健康基线）").isNotNull();

        System.out.println("[rootcause-e2e] result type: " + result.getClass().getName());
        System.out.println("[rootcause-e2e] result: " + result);

        // The result should be a forcedMap from RootCauseRail's forceFinish(degraded)
        // (tool failed → onToolException → pendingDegrade → afterModelCall → forceFinish)
        if (result instanceof Map<?, ?> map) {
            System.out.println("[rootcause-e2e] root_cause: " + map.get(RootCauseRail.ROOT_CAUSE_KEY));
            System.out.println("[rootcause-e2e] degraded: " + map.get(RootCauseRail.DEGRADED_KEY));
            // 软观察：rail 是否触发取决于 LLM 是否真调失败工具（非确定）。若触发，硬校验降级标记。
            if (map.containsKey(RootCauseRail.DEGRADED_KEY)) {
                assertThat(map.get(RootCauseRail.DEGRADED_KEY)).isEqualTo(true);
                assertThat(map.get(RootCauseRail.ROOT_CAUSE_KEY)).isEqualTo("DeviceFailure");
            }
        }
        // 软观察：此测试观察真 LLM 是否触发 device-failure 降级通道，非"证明通道成立"——
        // 通道装配由 RootCauseRailTest（mock 硬断言）承重。
    }

    @SuppressWarnings("rawtypes")
    static class AlwaysFailTool extends Tool {
        // inputParams 必填：缺 schema 时 LLM 倾向"叙述"使用工具而非真调（实测 GLM/deepseek 均如此）。
        private static final ToolCard CARD = ToolCard.builder()
                        .id("fetchData")
                        .name("fetchData")
                        .description("获取数据。参数：source（数据源名称，如 orders/users）。")
                        .inputParams(java.util.Map.of(
                                "type", "object",
                                "properties", java.util.Map.of(
                                        "source", java.util.Map.of("type", "string", "description", "数据源名称")),
                                "required", java.util.List.of("source")))
                        .build();

        public AlwaysFailTool() { super(CARD); }

        @Override
        public ToolCard getCard() { return CARD; }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            throw new RuntimeException("模拟设备故障：数据库连接超时");
        }

        @Override
        public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }
}