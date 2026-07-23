/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link VersatileAgentHandler} as the primary {@link AgentHandler}
 * bean for this deployment module.
 *
 * <p>Loaded before {@link AgentServiceAutoConfiguration} so the
 * {@code @ConditionalOnMissingBean(AgentHandler.class)} guard on the runtime's
 * placeholder {@code AgentHandlerHolder} skips cleanly. Without this
 * auto-configuration the runtime boots with no real handler and rejects all
 * queries with HTTP 503 (agent not ready).
 *
 * <p>Conditional on {@code openjiuwen.service.versatile.url-template} so the
 * handler is only registered when the deployment actually points at a
 * Versatile endpoint.
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = AgentServiceAutoConfiguration.class)
@ConditionalOnClass(VersatileAgentHandler.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile", name = "url-template")
public class VersatileIntentAutoConfiguration {

    /**
     * Creates the Versatile agent handler bean.
     *
     * @param properties the Versatile configuration properties
     * @return the Versatile agent handler
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public VersatileAgentHandler versatileAgentHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}
