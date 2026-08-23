/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.autoconfigure;

import com.openjiuwen.edp.handler.EdpAgentFactory;
import com.openjiuwen.edp.handler.EdpaExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * Auto-configuration that registers {@link EdpAgentFactory} as an {@link AgentFactory} Bean,
 * enabling DFX-002 per-Task Agent mode for edp-agent.
 *
 * <p><strong>Activation conditions</strong> (all must be met):</p>
 * <ol>
 *     <li>{@code AgentFactory} class is on the classpath — i.e., agent-runtime-ext-java
 *         includes DFX-002 concurrency support (agent-runtime ≥ DFX-002 version).
 *         When running on an older agent-runtime without DFX-002, this auto-configuration
 *         is silently skipped and the system falls back to singleton mode.</li>
 *     <li>{@link EdpaExtHandler.InitResult} Bean is available —
 *         registered by {@code EdpEngineConfiguration} from the singleton Agent's
 *         initialization result. Contains all shared config needed for per-Task agent creation.</li>
 *     <li>No other {@link AgentFactory} Bean exists — business projects may provide
 *         their own implementation, which takes precedence.</li>
 * </ol>
 *
 * <p><strong>Version compatibility</strong>:</p>
 * <ul>
 *     <li>New edp-agent + new agent-runtime (DFX-002): per-Task mode activated ✓</li>
 *     <li>New edp-agent + old agent-runtime (no DFX-002): gracefully degrades to singleton ✓</li>
 *     <li>Old edp-agent + new agent-runtime: singleton mode (no AgentFactory Bean) ✓</li>
 * </ul>
 *
 * @since 0.1.2
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory")
public class EdpAgentFactoryAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpAgentFactoryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(AgentFactory.class)
    @ConditionalOnBean(EdpaExtHandler.InitResult.class)
    AgentFactory edpAgentFactory(@Lazy EdpaExtHandler.InitResult initResult) {
        LOGGER.info("DFX-002: registering EdpAgentFactory for per-Task Agent mode, agentId={}",
                initResult.getDeepAgent().getCard().getId());
        return new EdpAgentFactory(initResult);
    }
}
