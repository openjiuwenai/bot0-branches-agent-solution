/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.studio.dsl.StudioDslModule;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.config.StudioDslProperties;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.PythonCodeExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * StudioDslAutoConfigurationTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class StudioDslAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StudioDslAutoConfiguration.class));

    @Test
    void loadsBeansWithDefaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StudioDslProperties.class);
            assertThat(ctx).hasSingleBean(StudioDslNodeProperties.class);
            assertThat(ctx).hasSingleBean(StudioDslModule.class);
            assertThat(ctx).hasSingleBean(NodeTypeRegistry.class);
            assertThat(ctx).hasSingleBean(WorkflowAssemblyBridge.class);
            assertThat(ctx).hasSingleBean(PythonCodeExecutor.class);
            assertThat(ctx.getBean(NodeTypeRegistry.class).canonicalTypes())
                    .hasSizeGreaterThanOrEqualTo(21)
                    .contains("jiuwen.start", "jiuwen.LLMComponent", "jiuwen.code");
            assertThat(ctx.getBean(StudioDslNodeProperties.class).getMaxNestingDepth()).isEqualTo(5);
        });
    }

    @Test
    void bindsNestedProperties() {
        runner.withPropertyValues(
                        "studio-dsl.nested-workflow.max-depth=3",
                        "studio-dsl.python.interpreter=python3.11",
                        "studio-dsl.code.java-spi-enabled=false")
                .run(ctx -> {
                    StudioDslNodeProperties props = ctx.getBean(StudioDslNodeProperties.class);
                    assertThat(props.getMaxNestingDepth()).isEqualTo(3);
                    assertThat(props.getPythonInterpreter()).isEqualTo("python3.11");
                    assertThat(props.isJavaSpiEnabled()).isFalse();
                });
    }

    @Test
    void canDisableViaProperty() {
        runner.withPropertyValues("studio-dsl.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(StudioDslModule.class);
            assertThat(ctx).doesNotHaveBean(NodeTypeRegistry.class);
        });
    }
}
