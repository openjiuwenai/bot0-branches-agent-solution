/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffTargetResolver;
import com.openjiuwen.service.adapters.versatile.controller.handoff.IntentHandoffClassifier;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Early auto-configuration registering the {@link ControllerHandoffAgentHandler}.
 *
 * <p>MUST run before {@link AgentServiceAutoConfiguration}: the runtime registers a
 * placeholder {@code AgentHandlerHolder} there via {@code @ConditionalOnMissingBean(
 * AgentHandler.class)} whenever {@code openjiuwen.service.agent-id} is not {@code false},
 * and the handoff handler itself claims the same slot the same way. Both conditions are
 * order-sensitive — whichever registers first wins — so this configuration is pinned
 * with {@code before = AgentServiceAutoConfiguration.class} to guarantee the handoff
 * handler (not the placeholder) serves {@code handoff.enabled=true} deployments.
 * The handler no longer depends on any late A2A bean: the outbound call is delegated
 * to the runtime coordinator via the a2a_delegate interrupt, so the target resolver
 * is registered here alongside the handler.
 *
 * @since 2026-08-19
 */
@AutoConfiguration(before = AgentServiceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.handoff", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties({VersatileProperties.class, ControllerHandoffProperties.class})
public class ControllerHandoffHandlerAutoConfiguration implements InitializingBean {

    private final ControllerHandoffProperties properties;

    public ControllerHandoffHandlerAutoConfiguration(ControllerHandoffProperties properties) {
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
    @ConditionalOnMissingBean(AgentHandler.class)
    public ControllerHandoffAgentHandler controllerHandoffAgentHandler(
            VersatileProperties versatileProperties,
            IntentHandoffClassifier classifier,
            HandoffTargetResolver targetResolver,
            ControllerHandoffProperties handoffProperties) {
        return new ControllerHandoffAgentHandler(versatileProperties, classifier,
                targetResolver, handoffProperties);
    }
}
