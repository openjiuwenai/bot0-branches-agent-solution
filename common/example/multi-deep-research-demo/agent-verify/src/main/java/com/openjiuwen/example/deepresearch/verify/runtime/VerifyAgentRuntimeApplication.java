/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify.runtime;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.example.deepresearch.verify.VerifyAgentFactory;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wrapper Spring Boot app that:
 * <ul>
 *   <li>Builds the {@link ReActAgent} via the library-tier factory. The factory
 *       manually attaches react-rails' {@code CriteriaReplanBridgeRail} +
 *       {@code RootCauseRail} — no BeanPostProcessor magic (the auto-config was
 *       removed in the {@code refactor: separate extension sdk boundaries} commit
 *       of PR#21).</li>
 *   <li>Exposes the agent through the standard {@link JiuwenCoreAgentHandler}.</li>
 * </ul>
 *
 * @since 2026-07-14
 */
@SpringBootApplication
@EnableConfigurationProperties(VerifyAgentSpringProperties.class)
public class VerifyAgentRuntimeApplication {
    /**
     * Spring Boot entry point.
     *
     * @param args command-line arguments forwarded to Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(VerifyAgentRuntimeApplication.class, args);
    }

    /**
     * Builds the verify-agent {@link AgentHandler} SPI bean.
     *
     * @param properties runtime configuration bound from {@code application.yml}
     * @param registrar optional middleware registrar provider
     * @return the configured {@link AgentHandler}
     */
    @Bean
    AgentHandler verifyAgentHandler(VerifyAgentSpringProperties properties,
                                    ObjectProvider<MiddlewareAdapterRegistrar> registrar) {
        ReActAgent agent = VerifyAgentFactory.build(properties);
        return new JiuwenCoreAgentHandler(agent, registrar.getIfAvailable());
    }
}
