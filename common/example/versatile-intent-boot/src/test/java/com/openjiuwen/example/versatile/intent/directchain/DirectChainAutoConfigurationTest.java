/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link DirectChainAutoConfiguration} 装配验证：启用时按 raw-passthrough 开关注册对应 handler，
 * 关闭时不注册。
 *
 * @since 0.1.0
 */
class DirectChainAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DirectChainAutoConfiguration.class)
            .withBean(com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.class)
            .withBean(A2AGatewayCardResolver.class, () -> {
                A2AGatewayProperties p = new A2AGatewayProperties();
                p.setBaseUrl("http://localhost:8084");
                p.setJsonRpcPath("/a2a/{agentCard}");
                return new A2AGatewayCardResolver(p);
            })
            .withPropertyValues("openjiuwen.service.versatile.url-template=http://x/{conversation_id}");

    @Test
    void registersDirectChainHandlerWhenEnabled() {
        runner.withPropertyValues(
                "openjiuwen.example.direct-chain.enabled=true",
                "openjiuwen.example.direct-chain.raw-passthrough=false")
                .run(ctx -> assertThat(ctx).getBean(AgentHandler.class)
                        .isInstanceOf(DirectChainVersatileAgentHandler.class));
    }

    @Test
    void registersRawPassthroughWhenRawMode() {
        runner.withPropertyValues(
                "openjiuwen.example.direct-chain.enabled=true",
                "openjiuwen.example.direct-chain.raw-passthrough=true")
                .run(ctx -> assertThat(ctx).getBean(AgentHandler.class)
                        .isInstanceOf(RawVersatilePassthroughHandler.class));
    }

    @Test
    void doesNotRegisterWhenDisabled() {
        runner.withPropertyValues("openjiuwen.example.direct-chain.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AgentHandler.class));
    }
}
