/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the {@code versatile-orchestration.gateway.plan-agent-protocol} property actually selects
 * the right {@link PlanAgentClient} bean via {@code @ConditionalOnProperty} — the core of the A2A
 * ↔ REST switch. Uses a non-web {@link ApplicationContextRunner} so the servlet container does not
 * start; the {@code @SpringBootApplication} component scan still registers the conditional
 * {@code @Component}s.
 */
class PlanAgentClientSelectionTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GatewayApplication.class);

    @Test
    void a2aClientSelectedWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(A2aPlanAgentClient.class)
                .doesNotHaveBean(RestPlanAgentClient.class)
                .getBean(PlanAgentClient.class)
                .isInstanceOf(A2aPlanAgentClient.class));
    }

    @Test
    void a2aClientSelectedWhenPropertyExplicit() {
        runner.withPropertyValues("versatile-orchestration.gateway.plan-agent-protocol=a2a")
                .run(ctx -> assertThat(ctx).getBean(PlanAgentClient.class)
                        .isInstanceOf(A2aPlanAgentClient.class));
    }

    @Test
    void restClientSelectedWhenPropertyRest() {
        runner.withPropertyValues("versatile-orchestration.gateway.plan-agent-protocol=rest")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(RestPlanAgentClient.class)
                        .doesNotHaveBean(A2aPlanAgentClient.class)
                        .getBean(PlanAgentClient.class)
                        .isInstanceOf(RestPlanAgentClient.class));
    }
}
