/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.customer.agent.customrest.ghzhy.EdpaGhzhyCustomRestAdapter;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * EDPAgent custom REST protocol adapter AutoConfiguration.
 *
 * <p>When {@code openjiuwen.service.custom-rest.query-path} is configured in application.yml,
 * this AutoConfiguration activates and registers {@link EdpaGhzhyCustomRestAdapter}
 * as the unique {@link CustomRestProtocolAdapter} implementation.</p>
 *
 * <p>When the property is not configured, this AutoConfiguration does not activate,
 * leaving the host process completely unaffected.</p>
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so the host process does not need to modify scanBasePackages.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "openjiuwen.service.custom-rest", name = "query-path")
public class EdpCustomRestAdapterAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpCustomRestAdapterAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CustomRestProtocolAdapter.class)
    CustomRestProtocolAdapter edpaGhzhyCustomRestAdapter(ObjectMapper objectMapper) {
        LOGGER.info("[EDP-CUSTOM-REST-CONF] Registering EdpaGhzhyCustomRestAdapter as CustomRestProtocolAdapter");
        return new EdpaGhzhyCustomRestAdapter(objectMapper);
    }

    @PostConstruct
    void logActivationStatus() {
        LOGGER.info("[EDP-CUSTOM-REST-CONF] EdpCustomRestAdapterAutoConfiguration active, query-path configured");
    }
}
