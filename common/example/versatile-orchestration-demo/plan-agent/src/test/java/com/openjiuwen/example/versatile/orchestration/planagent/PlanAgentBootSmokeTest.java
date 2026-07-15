/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * PlanAgentBootSmokeTest
 *
 * @since 2026-07-08
 */
class PlanAgentBootSmokeTest {
    /**
     * Validates that a ReActAgent wrapped in JiuwenCoreAgentExtHandler boots
     * cleanly under agent-service-app (the handler bean is created and the
     * context starts without an LLM key being required at construction time).
     */
    @Test
    void contextBootsWithReActAgentHandler() {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(PlanAgentApplication.class)
                            .run("--server.port=0",
                                    "--plan-agent.api-key=boot-smoke-not-used")) {
            assertThat(ctx.containsBean("planAgentHandler")).isTrue();
        }
    }
}
