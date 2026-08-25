/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.deepagent.IntentDeepAgentBinder;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.matcher.RerankerIntentMatcher;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.spi.IntentInitializer;
import com.openjiuwen.agents.intent.spi.IntentMatcher;
import com.openjiuwen.agents.intent.spi.IntentResultFunction;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.IntentDeepAgentInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.RuntimeIntentCatalogCoordinator;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Auto-configuration for DeepAgent intent routing.
 *
 * @since 2026-08-08
 */
@AutoConfiguration(after = AgentCoreExtAutoConfiguration.class)
@EnableConfigurationProperties(RuntimeIntentProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.intent", name = "enabled", havingValue = "true")
public class IntentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    IntentSuiteConfig intentSuiteConfig(RuntimeIntentProperties properties) {
        return RuntimeIntentCoreConfigFactory.create(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    IntentInitializer intentInitializer() {
        return new DefaultIntentInitializer();
    }

    @Bean
    @ConditionalOnMissingBean
    IntentMatcher intentMatcher(Reranker reranker, RuntimeIntentProperties properties) {
        return new RerankerIntentMatcher(reranker, properties.getMatch().getQueryInstruction());
    }

    @Bean
    @ConditionalOnMissingBean
    IntentSuite intentSuite(IntentSuiteConfig config, IntentInitializer initializer, IntentMatcher matcher) {
        return IntentSuite.builder(config).initializer(initializer).matcher(matcher).build();
    }

    @Bean
    @ConditionalOnMissingBean
    RuntimeConfiguredIntents runtimeConfiguredIntents(RuntimeIntentProperties properties,
            Map<String, IntentResultFunction> resultFunctions) {
        return RuntimeConfiguredIntentsFactory.create(properties, resultFunctions);
    }

    @Bean
    @ConditionalOnMissingBean
    RuntimeIntentCatalogCoordinator runtimeIntentCatalogCoordinator(IntentSuite suite,
            A2ARemoteAgentCardRegistry registry, RuntimeConfiguredIntents configuredIntents) {
        return new RuntimeIntentCatalogCoordinator(suite, registry, configuredIntents);
    }

    @Bean
    @ConditionalOnMissingBean
    IntentDeepAgentBinder intentDeepAgentBinder() {
        return new IntentDeepAgentBinder();
    }

    @Bean
    @ConditionalOnMissingBean
    IntentDeepAgentInstaller intentDeepAgentInstaller(IntentDeepAgentBinder binder, IntentSuite suite,
            A2ARemoteAgentCardRegistry registry, RuntimeIntentProperties properties) {
        return new IntentDeepAgentInstaller(binder, suite, registry, properties.isExposeAgentCardTools());
    }
}
