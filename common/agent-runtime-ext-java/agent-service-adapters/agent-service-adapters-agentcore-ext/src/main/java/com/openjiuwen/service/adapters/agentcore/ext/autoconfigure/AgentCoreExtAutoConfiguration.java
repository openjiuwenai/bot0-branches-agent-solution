/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aAgentCardCache;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aProperties;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(RemoteA2aProperties.class)
public class AgentCoreExtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${openjiuwen.service.handler:}' == 'agentcore-ext' "
            + "&& '${openjiuwen.service.agentcore-ext.remote-a2a.agents[0].url:}' != ''")
    RemoteA2aAgentCardCache remoteA2aAgentCardCache(RemoteA2aProperties properties,
                                                    A2ARemoteAgentCardRegistry registry) {
        return new RemoteA2aAgentCardCache(properties, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    RemoteA2aToolInstaller remoteA2aToolInstaller(
            org.springframework.beans.factory.ObjectProvider<RemoteA2aAgentCardCache> cache) {
        RemoteA2aAgentCardCache availableCache = cache.getIfAvailable();
        return RemoteA2aToolInstaller.create(availableCache);
    }
}
