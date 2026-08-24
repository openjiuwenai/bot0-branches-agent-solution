/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ControllerHandoffHandlerAutoConfiguration 条件装配验收：enabled=true 完整配置
 * 装配唯一 handler；缺省/enabled=false 不装配；识别条件不完整启动即失败。
 *
 * @since 2026-08-19
 */
class ControllerHandoffAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "openjiuwen.service.versatile.url-template=http://localhost:1/v1/p/a/c/{conversation_id}")
            .withUserConfiguration(ControllerHandoffHandlerAutoConfiguration.class);

    @Test
    void enabledWithCompleteConfigWiresSingleHandler() {
        runner.withPropertyValues(
                "openjiuwen.service.versatile.handoff.enabled=true",
                "openjiuwen.service.versatile.handoff.classify.field-path=/data/code",
                "openjiuwen.service.versatile.handoff.classify.field-value=14000")
                .run(context -> {
                    assertThat(context).hasSingleBean(ControllerHandoffAgentHandler.class);
                    assertThat(context.getBeansOfType(AgentHandler.class)).hasSize(1);
                });
    }

    @Test
    void disabledByDefaultProducesNoHandlerBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ControllerHandoffAgentHandler.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void enabledFalseProducesNoHandlerBeans() {
        runner.withPropertyValues("openjiuwen.service.versatile.handoff.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
    }

    @Test
    void incompleteClassifyConfigFailsStartup() {
        runner.withPropertyValues("openjiuwen.service.versatile.handoff.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("classify.field-path");
                });
    }
}
