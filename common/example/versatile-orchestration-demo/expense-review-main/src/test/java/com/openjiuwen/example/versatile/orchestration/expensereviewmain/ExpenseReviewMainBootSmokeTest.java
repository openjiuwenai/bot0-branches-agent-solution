/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereviewmain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Validates that a ReActAgent wrapped in JiuwenCoreAgentExtHandler boots cleanly
 * under agent-service-app (the handler bean is created and the context starts
 * without an LLM key being required at construction time).
 */
class ExpenseReviewMainBootSmokeTest {
    @Test
    void contextBootsWithReActAgentHandler() {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(ExpenseReviewMainApplication.class)
                            .run("--server.port=0",
                                    "--expense-review-main.api-key=boot-smoke-not-used")) {
            Object handler = ctx.getBean("expenseReviewMainHandler");
            assertThat(handler).isInstanceOf(JiuwenCoreAgentExtHandler.class);
        }
    }
}
