/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffExecutor;
import com.openjiuwen.service.adapters.versatile.controller.handoff.CrossAgentResumePort;
import com.openjiuwen.service.adapters.versatile.controller.handoff.DownstreamEventMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffLoopGuard;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffTargetResolver;
import com.openjiuwen.service.adapters.versatile.controller.handoff.IntentHandoffClassifier;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Versatile controller intent-handoff adapter. Active only
 * when {@code openjiuwen.service.versatile.handoff.enabled=true}; then the produced
 * {@link ControllerHandoffAgentHandler} is this instance's single AgentHandler (the
 * baseline VersatileAgentHandler registers no auto-configured bean — deployments that
 * additionally register it fail fast on duplicate AgentHandler injection; spec 4.7/6.3).
 * Self-enables the baseline VersatileProperties binding; the baseline auto-configuration
 * need not and cannot be excluded globally.
 *
 * @since 2026-08-19
 */
@AutoConfiguration(after = A2AAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.handoff", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties({VersatileProperties.class, ControllerHandoffProperties.class})
public class ControllerHandoffAutoConfiguration implements InitializingBean {

    private final ControllerHandoffProperties properties;

    public ControllerHandoffAutoConfiguration(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    /** 启动校验：识别配置不完整即失败，不允许静默进入运行（spec 1.3 case 1 / 6.4）。 */
    @Override
    public void afterPropertiesSet() {
        properties.validateRequiredIdentifiers();
    }

    @Bean
    @ConditionalOnMissingBean
    public IntentHandoffClassifier handoffClassifier(ControllerHandoffProperties props) {
        return new IntentHandoffClassifier(props);
    }

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
    public HandoffLoopGuard handoffLoopGuard(ControllerHandoffProperties props) {
        return new HandoffLoopGuard(props);
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

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public ControllerHandoffAgentHandler controllerHandoffAgentHandler(
            VersatileProperties versatileProperties,
            IntentHandoffClassifier classifier,
            HandoffLoopGuard loopGuard,
            ObjectProvider<ControllerHandoffExecutor> executorProvider,
            ControllerHandoffProperties handoffProperties) {
        return new ControllerHandoffAgentHandler(versatileProperties, classifier, loopGuard,
                executorProvider, handoffProperties);
    }
}
