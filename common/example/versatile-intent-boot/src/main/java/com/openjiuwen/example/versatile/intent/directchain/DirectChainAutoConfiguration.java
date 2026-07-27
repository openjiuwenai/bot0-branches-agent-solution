/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.example.versatile.intent.VersatileIntentAutoConfiguration;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 直链示例自动装配。在 {@code openjiuwen.example.direct-chain.enabled=true} 时，
 * 注册 {@link DirectChainVersatileAgentHandler} 覆盖默认的 versatile handler。
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = VersatileIntentAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.example.direct-chain", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DirectChainProperties.class)
public class DirectChainAutoConfiguration {
    /**
     * 注册中间层直链 handler：截胡 a2a_delegate 中断，改为经 gateway 隧道直链目标 /v1/query。
     *
     * @param versatileProps versatile 全局配置
     * @param props          直链配置
     * @param gatewayResolver a2a gateway 卡片解析器
     * @return 直链 versatile agent handler
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandler directChainVersatileAgentHandler(VersatileProperties versatileProps,
            DirectChainProperties props, A2AGatewayCardResolver gatewayResolver) {
        return new DirectChainVersatileAgentHandler(versatileProps, props, gatewayResolver);
    }
}
