/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayRemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: verify the Spring context loads under the {@code layer1} profile
 * and the gateway Caller/Resolver override the Default* beans.
 *
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("layer1")
class ProfileLayer1LoadTest {
    @Autowired
    private RemoteAgentCaller remoteAgentCaller;

    @Autowired
    private RemoteAgentCardResolver remoteAgentCardResolver;

    @Test
    void contextLoads() {
    }

    @Test
    void gatewayBeansOverrideDefaults() {
        assertThat(remoteAgentCaller).isInstanceOf(A2AGatewayRemoteAgentCaller.class);
        assertThat(remoteAgentCardResolver).isInstanceOf(A2AGatewayCardResolver.class);
    }
}
