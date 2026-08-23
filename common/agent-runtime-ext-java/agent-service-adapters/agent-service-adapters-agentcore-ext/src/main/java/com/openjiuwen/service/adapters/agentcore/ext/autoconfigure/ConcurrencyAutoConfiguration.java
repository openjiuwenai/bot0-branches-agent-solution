/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.ConcurrencyProperties;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskAdmissionControl;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskQuotaTracker;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * Auto-configuration for DFX-002 runtime concurrency and throttling.
 *
 * <p>Registers admission gate, quota tracker, and agent instance manager
 * beans. When this configuration is excluded (or the ext module is absent),
 * all concurrency beans are absent and the runtime degrades to unlimited
 * concurrency (max=-1).
 *
 * @since 0.1.2
 */
@AutoConfiguration
@EnableConfigurationProperties(ConcurrencyProperties.class)
public class ConcurrencyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TaskAdmissionGate.class)
    TaskAdmissionControl taskAdmissionControl(ConcurrencyProperties props) {
        return new TaskAdmissionControl(props.getMaxConcurrentTasks());
    }

    @Bean
    @ConditionalOnMissingBean(ActiveTaskQuery.class)
    TaskQuotaTracker taskQuotaTracker(TaskAdmissionControl admissionControl) {
        return new TaskQuotaTracker(admissionControl);
    }

    @Bean
    @ConditionalOnBean(AgentFactory.class)
    @ConditionalOnMissingBean
    AgentInstanceManager agentInstanceManager(@Lazy AgentFactory factory) {
        return new AgentInstanceManager(factory);
    }
}
