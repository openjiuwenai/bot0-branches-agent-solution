/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers {@link MockA2aGatewayProperties} for the mock A2A gateway profile.
 * Component-scanned (same package as {@link MockA2AGatewayController}); no
 * AutoConfiguration.imports entry needed.
 *
 * @since 0.1.0
 */
@Configuration
@Profile("mock-a2a-gateway")
@EnableConfigurationProperties(MockA2aGatewayProperties.class)
public class MockA2aGatewayConfiguration {
}
