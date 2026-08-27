/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判别实验：最小写入任务——模型只需调用 write_artifact 照抄给定 JSON。
 * 区分：①FC 下 write_artifact 工具调用拒绝（schema/参数问题）
 *      ②大 JSON 自主构建犹豫（完整任务的认知负担）。
 *
 * @since 2026-08
 */
class MinimalWriteE2eTest {

    private static final String TASK = """
            Call the write_artifact tool NOW with:
            path: "out/v2a5.json"
            content: {"baseline": {"file": "data/cand-k05.csv", "rows": 50}, "intervention": {"file": "data/cand-m02.csv", "rows": 51}, "followup": {"file": "data/cand-p10.csv", "rows": 51}, "joint_window": {"start": "2026-03-08", "end": "2026-05-01", "days": 54}, "margin_days": 41}
            Then reply DONE.""";

    @Test
    void minimalWriteDiscriminator() throws Exception {
        // R3-snapshot：空串 env 误跑（只查 non-null）——isBlank 对齐其余 e2e
        Assumptions.assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null
                && !System.getenv("DEEPSEEK_API_KEY").isBlank()
                && System.getenv("DEEPSEEK_BASE_URL") != null, "env-gated");
        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        String model = System.getenv().getOrDefault("GLH_MODEL", "deepseek-v4-flash");

        Path work = Files.createTempDirectory("minwrite");
        Files.createDirectories(work.resolve("out"));

        com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories.ensureRegistered();
        var cli = com.openjiuwen.core.foundation.llm.schema.ModelClientConfig.builder()
                .clientId("minwrite-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var req = com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig.builder()
                .modelName(model).temperature(0.2).topP(0.95).maxTokens(4000).build();
        var agent = new com.openjiuwen.core.singleagent.agents.ReActAgent(
                com.openjiuwen.core.singleagent.schema.AgentCard.builder().name("minwrite").build());
        agent.setLlm(new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cli, req));
        if (agent.getConfig() instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig cfg) {
            cfg.configureMaxIterations(5);
        }
        BenchTools bench = new BenchTools(work, work);
        for (var t : bench.tools()) {
            agent.getAbilityManager().add(t.getCard());
            com.openjiuwen.core.runner.Runner.resourceMgr().addTool(t, agent.getCard().getId());
        }

        long start = System.currentTimeMillis();
        Object raw = agent.invoke(TASK, null);
        long elapsed = System.currentTimeMillis() - start;

        boolean wrote = Files.isRegularFile(work.resolve("out/v2a5.json"));
        System.out.println("[minwrite] elapsed=" + elapsed + "ms wrote=" + wrote
                + " reply=" + String.valueOf(raw).substring(0,
                        Math.min(120, String.valueOf(raw).length())));
        if (wrote) {
            System.out.println("[minwrite] content=" + Files.readString(work.resolve("out/v2a5.json")));
        }
        System.out.println("[minwrite] 判定: " + (wrote
                ? "工具调用正常 → 完整任务的问题=大 JSON 自主构建犹豫"
                : "最小任务也不写 → write_artifact 工具面问题（schema/参数）"));

        assertThat(wrote).as("最小写入任务模型应能完成").isTrue();
    }
}
