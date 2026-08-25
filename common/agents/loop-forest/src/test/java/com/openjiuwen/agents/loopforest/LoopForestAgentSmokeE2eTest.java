/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一入口冒烟——一个 builder 得到完整能力（真 LLM，env-gated）。
 *
 * @since 2026-08
 */
class LoopForestAgentSmokeE2eTest {

    @Test
    void builderProducesWorkingAgent() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        String base = System.getenv("DEEPSEEK_BASE_URL");
        Assumptions.assumeTrue(key != null && !key.isBlank()
                && base != null && !base.isBlank(), "env-gated");

        LoopForestAgent agent = LoopForestAgent.builder()
                .apiKey(key).apiBase(base)
                .model(System.getenv().getOrDefault("GLH_MODEL", "deepseek-v4-flash"))
                .build();

        Object result = agent.invoke("Reply with exactly: FOREST-OK");
        System.out.println("[smoke] result=" + result);
        assertThat(String.valueOf(result)).contains("FOREST-OK");
    }
}
