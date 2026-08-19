/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffExecutor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerHandoffAutoConfigurationTest {

    static final class FakeCaller implements RemoteAgentCaller {
        @Override
        public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver observer) {
            return CompletableFuture.completedFuture(new RemoteCallOutcome("rt", TaskState.TASK_STATE_COMPLETED,
                    "COMPLETED", null, null, null));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "openjiuwen.service.versatile.url-template=http://localhost:1/v1/p/a/c/{conversation_id}")
            .withUserConfiguration(ControllerHandoffAutoConfiguration.class,
                    ControllerHandoffHandlerAutoConfiguration.class)
            .withBean("fakeCaller", RemoteAgentCaller.class, FakeCaller::new);

    @Test
    void enabledWithCompleteConfigWiresSingleHandlerAndExecutor() {
        runner.withPropertyValues(
                "openjiuwen.service.versatile.handoff.enabled=true",
                "openjiuwen.service.versatile.handoff.classify.field-path=/data/code",
                "openjiuwen.service.versatile.handoff.classify.field-value=14000")
                .run(context -> {
                    assertThat(context).hasSingleBean(ControllerHandoffAgentHandler.class);
                    assertThat(context).hasSingleBean(ControllerHandoffExecutor.class);
                    assertThat(context.getBeansOfType(AgentHandler.class)).hasSize(1);
                });
    }

    @Test
    void disabledByDefaultProducesNoHandlerBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ControllerHandoffAgentHandler.class);
            assertThat(context).doesNotHaveBean(ControllerHandoffExecutor.class);
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

    @Test
    void missingRemoteCallerStillWiresHandlerWithoutExecutor() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "openjiuwen.service.versatile.url-template=http://localhost:1/v1/p/a/c/{conversation_id}",
                        "openjiuwen.service.versatile.handoff.enabled=true",
                        "openjiuwen.service.versatile.handoff.classify.field-path=/data/code",
                        "openjiuwen.service.versatile.handoff.classify.field-value=14000")
                .withUserConfiguration(ControllerHandoffAutoConfiguration.class,
                    ControllerHandoffHandlerAutoConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ControllerHandoffAgentHandler.class);
                    assertThat(context).doesNotHaveBean(ControllerHandoffExecutor.class);
                });
    }
}
