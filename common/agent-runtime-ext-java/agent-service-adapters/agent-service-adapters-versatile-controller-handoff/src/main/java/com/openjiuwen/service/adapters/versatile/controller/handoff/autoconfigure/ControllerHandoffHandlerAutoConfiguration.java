/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ForwardContextIdRemoteAgentCaller;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffTargetResolver;
import com.openjiuwen.service.adapters.versatile.controller.handoff.IntentHandoffClassifier;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
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
 * <p>Also runs before {@link A2AAutoConfiguration} so the optional
 * {@code forward.context-id-prefix-target} caller can claim the
 * {@code RemoteAgentCaller} slot before the runtime's conditional default
 * (same order-sensitive pattern).
 *
 * @since 2026-08-19
 */
@AutoConfiguration(before = {AgentServiceAutoConfiguration.class, A2AAutoConfiguration.class})
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.handoff", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties({VersatileProperties.class, ControllerHandoffProperties.class})
public class ControllerHandoffHandlerAutoConfiguration implements InitializingBean {
    private final ControllerHandoffProperties properties;

    public ControllerHandoffHandlerAutoConfiguration(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动校验：识别配置不完整即失败，不允许静默进入运行（spec 1.3 case 1 / 6.4）。
     */
    @Override
    public void afterPropertiesSet() {
        properties.validateRequiredIdentifiers();
    }

    /**
     * 注册默认转调分类器。
     *
     * @param props handoff 配置
     * @return 分类器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public IntentHandoffClassifier handoffClassifier(ControllerHandoffProperties props) {
        return new IntentHandoffClassifier(props);
    }

    /**
     * 注册默认目标解析器。
     *
     * @param props handoff 配置
     * @return 目标解析器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HandoffTargetResolver handoffTargetResolver(ControllerHandoffProperties props) {
        return new HandoffTargetResolver(props);
    }

    /**
     * 注册控制器转调 handler，占位 AgentHandler 槽位（顺序敏感，见类注释）。
     *
     * @param versatileProperties Versatile 基线配置
     * @param classifier 转调分类器
     * @param targetResolver 目标解析器
     * @param handoffProperties handoff 配置
     * @return 控制器转调 handler
     */
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

    /**
     * Optional outbound contextId rewrite ({@code forward.context-id-prefix-target}):
     * replaces the runtime's conditional default caller with the
     * {@link ForwardContextIdRemoteAgentCaller} decorator — every outbound A2A
     * call carries {@code <目标agentId>-<原contextId>}. {@code @ConditionalOnMissingBean}
     * keeps user-provided callers in charge.
     *
     * @param registry runtime-managed remote Agent Card registry
     * @param a2aProperties runtime A2A configuration
     * @return contextId-prefixing remote caller
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.versatile.handoff.forward",
            name = "context-id-prefix-target", havingValue = "true")
    @ConditionalOnMissingBean(RemoteAgentCaller.class)
    public RemoteAgentCaller forwardContextIdRemoteAgentCaller(A2ARemoteAgentCardRegistry registry,
            A2AProperties a2aProperties) {
        A2ARemoteAgentClient delegate = new A2ARemoteAgentClient(registry,
                a2aProperties.getRemoteInvocation().getMaxConcurrency());
        return new ForwardContextIdRemoteAgentCaller(delegate);
    }
}
