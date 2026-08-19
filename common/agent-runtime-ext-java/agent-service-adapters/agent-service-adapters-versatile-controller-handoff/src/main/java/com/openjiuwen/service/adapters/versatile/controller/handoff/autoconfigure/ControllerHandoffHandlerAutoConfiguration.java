/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffExecutor;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffLoopGuard;
import com.openjiuwen.service.adapters.versatile.controller.handoff.IntentHandoffClassifier;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
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
 * handler (not the placeholder) serves {@code handoff.enabled=true} deployments. The
 * executor stays in {@link ControllerHandoffAutoConfiguration}, which runs after the
 * A2A client auto-configuration so the default {@code RemoteAgentCaller} bean is visible
 * to its {@code @ConditionalOnBean}; the handler resolves the executor lazily via
 * {@link ObjectProvider}, so the split ordering is safe.
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
    public HandoffLoopGuard handoffLoopGuard(ControllerHandoffProperties props) {
        return new HandoffLoopGuard(props);
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
