/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search.runtime;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.example.deepresearch.search.SearchAgentFactory;
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
 *   <li>Builds the {@link ReActAgent} via the library-tier factory, with the
 *       {@code web_search} Java tool registered on its {@code AbilityManager}.</li>
 *   <li>Exposes it through the standard {@link JiuwenCoreAgentHandler} — no remote
 *       A2A sub-agent injection is needed here (this agent <em>is</em> the leaf).</li>
 * </ul>
 *
 * <p>Profile switching: {@code openjiuwen.demo.search-agent.use-stub=true} (set
 * under the {@code stub} Spring profile in {@code application.yml}) makes the
 * factory wire {@code StubWebSearchTool} instead of {@code WebSearchTool}.
 *
 * @since 2026-07-06
 */
@SpringBootApplication
@EnableConfigurationProperties(SearchAgentSpringProperties.class)
public class SearchAgentRuntimeApplication {
    /**
     * Spring Boot entry point.
     *
     * @param args command-line arguments forwarded to Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(SearchAgentRuntimeApplication.class, args);
    }

    /**
     * Builds the search-agent {@link AgentHandler} SPI bean.
     *
     * @param properties runtime configuration bound from {@code application.yml}
     * @param registrar optional middleware registrar provider
     * @return the configured {@link AgentHandler}
     */
    @Bean
    AgentHandler searchAgentHandler(SearchAgentSpringProperties properties,
                                    ObjectProvider<MiddlewareAdapterRegistrar> registrar) {
        ReActAgent agent = SearchAgentFactory.build(properties);
        return new JiuwenCoreAgentHandler(agent, registrar.getIfAvailable());
    }
}
