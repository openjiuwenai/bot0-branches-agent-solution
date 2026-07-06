/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Versatile A2A adapter demo application context.
 *
 * @since 2026-06-30
 */
@SpringBootTest(properties = {
        "openjiuwen.service.versatile.url-template=http://127.0.0.1:1"
})
class VersatileA2AAdapterDemoApplicationTest {
    @Autowired
    private AgentHandler agentHandler;

    @Test
    void startsWithVersatileAgentHandler() {
        assertThat(agentHandler).isInstanceOf(VersatileAgentHandler.class);
    }
}
