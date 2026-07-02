/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for AgentCore extension adapters.
 *
 * @since 2026-06-30
 */
@AutoConfiguration
public class AgentCoreExtAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    RemoteA2aToolInstaller remoteA2aToolInstaller(ObjectProvider<A2ARemoteAgentCardRegistry> registry) {
        return RemoteA2aToolInstaller.create(registry.getIfAvailable());
    }
}
