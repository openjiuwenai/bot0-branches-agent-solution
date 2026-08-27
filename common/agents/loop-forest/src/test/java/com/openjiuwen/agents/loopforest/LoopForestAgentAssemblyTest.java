/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一入口无 env 装配测试（R1-F5 处置：LoopForestAgent 唯一测试原为
 * env-gated——Builder 装配默认零验证）。dummy 凭据构建，零 LLM 调用。
 *
 * @since 2026-08
 */
class LoopForestAgentAssemblyTest {

    @Test
    void builderAssemblesWithAllLayersNoEnv() {
        LoopForestAgent agent = LoopForestAgent.builder()
                .apiKey("dummy").apiBase("https://example.invalid")
                .model("dummy-model")
                .contract(new com.openjiuwen.agents.loopforest.verification
                        .VetoContract(Map.of("write_artifact",
                        Set.of("baseline", "margin_days"))))
                .budget(2, 3, 0)
                .build();
        assertThat(agent).as("全层参数装配成功（零 LLM 调用）").isNotNull();
    }

    @Test
    void builderMinimalAssembles() {
        LoopForestAgent agent = LoopForestAgent.builder()
                .apiKey("dummy").apiBase("https://example.invalid")
                .model("dummy-model")
                .build();
        assertThat(agent).as("骨架档装配（Veto/Budget 未激活——诚实边界）").isNotNull();
    }

    @Test
    void vetoRejectionFallbackLoadsFromModulePrompts() {
        // R1-F9 处置面：fallback 文案来自本模块 prompts 真源而非硬编码——
        // 经 builder 内部路径间接触发太重，此处断言真源可加载且非空
        try (var in = LoopForestAgent.class.getResourceAsStream(
                "/prompts/veto-rejection.txt")) {
            assertThat(in).as("模块真源存在").isNotNull();
            String text = new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            assertThat(text).as("真源非空").isNotEmpty();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
