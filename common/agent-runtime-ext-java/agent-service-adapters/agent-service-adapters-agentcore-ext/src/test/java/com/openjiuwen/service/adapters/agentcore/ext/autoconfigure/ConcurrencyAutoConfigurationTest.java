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
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;

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
    void tracker_isAdmissionListenerDriven() {
        // The tracker must be registered as a TaskAdmissionListener so the
        // A2A executor (not the AgentHandler) drives probe recording.
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskAdmissionListener.class);
            assertThat(context.getBean(TaskAdmissionListener.class)).isSameAs(context.getBean(TaskQuotaTracker.class));
            context.getBean(TaskAdmissionListener.class).onAdmitted("t-1", "c-1");
            assertThat(context.getBean(ActiveTaskQuery.class).snapshot().getCurrentActiveTasks()).isEqualTo(1);
            context.getBean(TaskAdmissionListener.class).onReleased("t-1", "c-1");
            assertThat(context.getBean(ActiveTaskQuery.class).snapshot().getCurrentActiveTasks()).isZero();
        });
    }

    @Test
    void customGate_backsOffDefaultControl_trackerStillRegistered() {
        // A user-defined TaskAdmissionGate must back off the default
        // TaskAdmissionControl without breaking context startup: the tracker
        // switches to the gate interface and reports the custom limit.
        TaskAdmissionGate customGate = new TaskAdmissionGate() {
            @Override
            public boolean tryAcquire() {
                return true;
            }

            @Override
            public void release() {
                // no-op
            }

            @Override
            public int currentCount() {
                return 0;
            }

            @Override
            public int limit() {
                return 7;
            }

            @Override
            public void shutdown() {
                // no-op
            }

            @Override
            public void reset() {
                // no-op
            }
        };
        runner.withBean("customAdmissionGate", TaskAdmissionGate.class, () -> customGate).run(context -> {
            assertThat(context).doesNotHaveBean(TaskAdmissionControl.class);
            assertThat(context.getBean(TaskAdmissionGate.class)).isSameAs(customGate);
            assertThat(context).hasSingleBean(TaskQuotaTracker.class);
            assertThat(context).hasSingleBean(ActiveTaskQuery.class);
            assertThat(context.getBean(TaskAdmissionListener.class)).isSameAs(context.getBean(TaskQuotaTracker.class));
            assertThat(context.getBean(ActiveTaskQuery.class).snapshot().getMaxConcurrentTasks())
                    .isEqualTo(7);
        });
    }

    @Test
    void maxConcurrentTasks_zero_failsStartup() {
        runner.withPropertyValues("openjiuwen.service.concurrency.max-concurrent-tasks=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(
                            "openjiuwen.service.concurrency.max-concurrent-tasks=0");
                });
    }

    @Test
    void maxConcurrentTasks_belowMinusOne_failsStartup() {
        runner.withPropertyValues("openjiuwen.service.concurrency.max-concurrent-tasks=-2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(
                            "openjiuwen.service.concurrency.max-concurrent-tasks=-2");
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
    void noFactory_agentMgrAbsent_othersRegistered() {
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
