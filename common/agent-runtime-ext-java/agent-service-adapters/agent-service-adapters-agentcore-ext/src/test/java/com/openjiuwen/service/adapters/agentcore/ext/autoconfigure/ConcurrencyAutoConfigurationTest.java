/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskAdmissionControl;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskQuotaTracker;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Auto-configuration tests for {@link ConcurrencyAutoConfiguration}
 * (DFX-002 A-1~A-6).
 *
 * @since 0.1.2
 */
class ConcurrencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConcurrencyAutoConfiguration.class))
            .withBean(AgentFactory.class, () -> new AgentFactory() {
                @Override
                public Object create() {
                    return new Object();
                }

                @Override
                public void destroy(Object agent) {
                    // no-op
                }
            });

    @Test
    void registersAllBeans_byDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskAdmissionControl.class);
            assertThat(context).hasSingleBean(AgentInstanceManager.class);
            assertThat(context).hasSingleBean(TaskQuotaTracker.class);
        });
    }

    @Test
    void admissionGate_isTaskAdmissionGateInterface() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskAdmissionGate.class);
            assertThat(context.getBean(TaskAdmissionGate.class)).isInstanceOf(TaskAdmissionControl.class);
        });
    }

    @Test
    void maxConcurrentTasks_negativeOne_tryAcquireAlwaysTrue() {
        runner.withPropertyValues("openjiuwen.service.concurrency.max-concurrent-tasks=-1")
                .run(context -> {
                    TaskAdmissionGate gate = context.getBean(TaskAdmissionGate.class);
                    assertThat(gate.tryAcquire()).isTrue();
                    assertThat(gate.tryAcquire()).isTrue();
                });
    }

    @Test
    void maxConcurrentTasks_positive_enforcesLimit() {
        runner.withPropertyValues("openjiuwen.service.concurrency.max-concurrent-tasks=2")
                .run(context -> {
                    TaskAdmissionGate gate = context.getBean(TaskAdmissionGate.class);
                    assertThat(gate.tryAcquire()).isTrue();
                    assertThat(gate.tryAcquire()).isTrue();
                    assertThat(gate.tryAcquire()).isFalse();
                });
    }

    @Test
    void activeTaskQueryBean_isTaskQuotaTracker() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ActiveTaskQuery.class);
            assertThat(context.getBean(ActiveTaskQuery.class)).isInstanceOf(TaskQuotaTracker.class);
        });
    }

    @Test
    void beansAbsent_whenConcurrencyAutoConfigExcluded() {
        new ApplicationContextRunner()
                .withBean(AgentFactory.class, () -> new AgentFactory() {
                    @Override
                    public Object create() {
                        return new Object();
                    }

                    @Override
                    public void destroy(Object agent) {
                        // no-op
                    }
                })
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TaskAdmissionGate.class);
                    assertThat(context).doesNotHaveBean(ActiveTaskQuery.class);
                    assertThat(context).doesNotHaveBean(AgentInstanceManager.class);
                });
    }

    @Test
    void noAgentFactoryBean_agentInstanceManagerAbsent_othersStillRegistered() {
        // Negative branch of @ConditionalOnBean(AgentFactory.class): without a
        // factory the per-Task agent manager must NOT be created (handler falls
        // back to singleton mode), while admission control and task tracking
        // must still be registered so limiting and the query endpoint work.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConcurrencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentInstanceManager.class);
                    assertThat(context).hasSingleBean(TaskAdmissionControl.class);
                    assertThat(context).hasSingleBean(TaskQuotaTracker.class);
                    assertThat(context).hasSingleBean(TaskAdmissionGate.class);
                    assertThat(context).hasSingleBean(ActiveTaskQuery.class);
                });
    }
}
