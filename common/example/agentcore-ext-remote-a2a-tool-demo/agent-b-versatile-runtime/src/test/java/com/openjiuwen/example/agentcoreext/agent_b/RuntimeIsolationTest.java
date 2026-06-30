/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agent_b;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies runtime classpath isolation for the demo application.
 *
 * @since 2026-06-30
 */
class RuntimeIsolationTest {

    @Test
    void agentAApplicationIsNotOnAgentBClasspath() {
        assertThat(classExists("com.openjiuwen.example.agentcoreext.agent_a.DeepAgentRuntimeApplication"))
                .isFalse();
    }

    @Test
    void agentBDeclaresExactlyOneAgentHandlerFactoryMethod() {
        long handlerFactoryMethods = java.util.Arrays.stream(VersatileRuntimeApplication.class.getDeclaredMethods())
                .filter(method -> AgentHandler.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(handlerFactoryMethods).isEqualTo(1);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
