package com.openjiuwen.example.agentcoreext.agent_a;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeIsolationTest {

    @Test
    void agentBApplicationIsNotOnAgentAClasspath() {
        assertThat(classExists("com.openjiuwen.example.agentcoreext.agent_b.VersatileRuntimeApplication"))
                .isFalse();
    }

    @Test
    void agentADeclaresExactlyOneAgentHandlerFactoryMethod() {
        long handlerFactoryMethods = java.util.Arrays.stream(DeepAgentRuntimeApplication.class.getDeclaredMethods())
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
