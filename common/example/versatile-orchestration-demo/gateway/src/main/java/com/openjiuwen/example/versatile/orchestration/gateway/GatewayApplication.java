/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * edpa gateway: accepts the flat EDPA request body posted to
 * {@code /v1/{projectId}/agents/{agentId}/conversations/{cid}} ({@code input.query},
 * {@code conversation_id}, {@code custom_data.inputs}, …), translates it to a clean A2A
 * {@code SendStreamingMessage} for the plan-agent, forwards it, and streams the SSE response
 * back while managing INPUT_REQUIRED task-resume state.
 *
 * @since 2026-07-08
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
