/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereview;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Validates that the WorkflowAgent wrapped in JiuwenCoreAgentHandler boots cleanly
 * under agent-service-app (the handler bean is created and the context starts without
 * an LLM key being required at construction time).
 */
class ExpenseReviewBootSmokeTest {
    @Test
    void contextBootsWithWorkflowAgentHandler() {
        try (ConfigurableApplicationContext ctx =
                    new SpringApplicationBuilder(ExpenseReviewApplication.class)
                            .run("--server.port=0",
                                    "--expense-review.api-key=boot-smoke-not-used")) {
            Object handler = ctx.getBean("expenseReviewHandler");
            assertThat(handler).isInstanceOf(JiuwenCoreAgentHandler.class);
        }
    }
}
