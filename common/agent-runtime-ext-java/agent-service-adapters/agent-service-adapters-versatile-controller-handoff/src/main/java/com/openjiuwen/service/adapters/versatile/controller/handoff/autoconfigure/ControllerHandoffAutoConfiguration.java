/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffExecutor;
import com.openjiuwen.service.adapters.versatile.controller.handoff.CrossAgentResumePort;
import com.openjiuwen.service.adapters.versatile.controller.handoff.DownstreamEventMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffLoopGuard;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffTargetResolver;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Late auto-configuration for the handoff executor pipeline. Runs after
 * {@link A2AAutoConfiguration} so a default {@code RemoteAgentCaller} bean (runtime
 * {@code A2ARemoteAgentClient}) is visible to the executor's
 * {@code @ConditionalOnBean}; deployments registering their own caller bean (user
 * configuration) win the {@code @ConditionalOnMissingBean} slot earlier still.
 *
 * <p>The {@code AgentHandler} bean itself lives in
 * {@link ControllerHandoffHandlerAutoConfiguration}, which must run before the
 * runtime's {@code AgentServiceAutoConfiguration} placeholder holder — see there
 * for the ordering rationale.
 *
 * @since 2026-08-19
 */
@AutoConfiguration(after = A2AAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.handoff", name = "enabled",
        havingValue = "true")
public class ControllerHandoffAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HandoffTargetResolver handoffTargetResolver(ControllerHandoffProperties props) {
        return new HandoffTargetResolver(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DownstreamEventMapper downstreamEventMapper() {
        return new DownstreamEventMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RemoteAgentCaller.class)
    public ControllerHandoffExecutor handoffExecutor(RemoteAgentCaller remoteAgentCaller,
            HandoffTargetResolver targetResolver,
            DownstreamEventMapper eventMapper,
            HandoffLoopGuard loopGuard,
            ObjectProvider<CrossAgentResumePort> resumePortProvider,
            ControllerHandoffProperties props) {
        return new ControllerHandoffExecutor(remoteAgentCaller, targetResolver, eventMapper, loopGuard,
                resumePortProvider, props);
    }
}
