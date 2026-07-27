/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fixture store and the two handler beans used by the JSON-RPC controller.
 *
 * @since 2026-07-07
 */
@Configuration
@EnableConfigurationProperties(McpDocServerProperties.class)
public class McpDocServerConfiguration {
    /**
     * Builds the singleton fixture store from the configured classpath prefix.
     *
     * @param props external MCP doc server configuration
     * @return the fixture store
     */
    @Bean
    public DocumentFixtureStore documentFixtureStore(McpDocServerProperties props) {
        return new DocumentFixtureStore(props.getFixturesClasspath());
    }

    /**
     * Builds the resource handler bean.
     *
     * @param store the fixture store
     * @return the resource handler
     */
    @Bean
    public McpResourceHandlers mcpResourceHandlers(DocumentFixtureStore store) {
        return new McpResourceHandlers(store);
    }

    /**
     * Builds the tool handler bean.
     *
     * @param store the fixture store
     * @return the tool handler
     */
    @Bean
    public McpToolHandlers mcpToolHandlers(DocumentFixtureStore store) {
        return new McpToolHandlers(store);
    }
}
