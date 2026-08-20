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
 * Late auto-configuration for the legacy handoff executor pipeline (pending retirement,
 * migration design 5 阶段 3): no longer referenced by the handler — the outbound call is
 * delegated to the runtime coordinator via the a2a_delegate interrupt. Runs after
 * {@link A2AAutoConfiguration} so a default {@code RemoteAgentCaller} bean (runtime
 * {@code A2ARemoteAgentClient}) is visible to the executor's {@code @ConditionalOnBean}.
 *
 * <p>The {@code AgentHandler} bean and the {@link HandoffTargetResolver} live in
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
