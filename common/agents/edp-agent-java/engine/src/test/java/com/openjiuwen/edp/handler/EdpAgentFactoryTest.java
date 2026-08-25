/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.edp.config.EdpConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.edp.handler.EdpaExtHandler.InitResult;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link EdpAgentFactory}.
 *
 * @since 0.1.2
 */
class EdpAgentFactoryTest {
    private static InitResult createInitResult(AgentCard card, DeepAgentConfig config) {
        DeepAgent mockDeepAgent = mock(DeepAgent.class);
        when(mockDeepAgent.getCard()).thenReturn(card);
        when(mockDeepAgent.getConfig()).thenReturn(config);
        InitResult initResult = new InitResult();
        initResult.setDeepAgent(mockDeepAgent);
        initResult.setEdpConfig(new EdpConfig());
        return initResult;
    }

    @Test
    void create_callsHarnessFactory_andReturnsNewInstance() {
        AgentCard card = AgentCard.builder().id("test-agent").name("Test Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent mockAgent = mock(DeepAgent.class);
        when(mockAgent.getCard()).thenReturn(card);
        // Mock inner agent for initPerTaskAgent calls (registerRail etc.)
        doReturn(mock(ReActAgent.class)).when(mockAgent).getAgent();

        try (MockedStatic<HarnessFactory> mockedHarness = Mockito.mockStatic(HarnessFactory.class)) {
            mockedHarness.when(() -> HarnessFactory.createDeepAgent(card, config, null))
                    .thenReturn(mockAgent);

            EdpAgentFactory factory = new EdpAgentFactory(createInitResult(card, config));
            Object result = factory.create();

            assertThat(result).isSameAs(mockAgent);
            mockedHarness.verify(() -> HarnessFactory.createDeepAgent(card, config, null));
            verify(mockAgent).ensureInitialized();
        }
    }

    @Test
    void create_multipleCalls_returnNewInstances() {
        AgentCard card = AgentCard.builder().id("multi-agent").name("Multi Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent agent1 = mock(DeepAgent.class);
        DeepAgent agent2 = mock(DeepAgent.class);
        when(agent1.getCard()).thenReturn(card);
        when(agent2.getCard()).thenReturn(card);
        doReturn(mock(ReActAgent.class)).when(agent1).getAgent();
        doReturn(mock(ReActAgent.class)).when(agent2).getAgent();

        try (MockedStatic<HarnessFactory> mockedHarness = Mockito.mockStatic(HarnessFactory.class)) {
            mockedHarness.when(() -> HarnessFactory.createDeepAgent(card, config, null))
                    .thenReturn(agent1, agent2);

            EdpAgentFactory factory = new EdpAgentFactory(createInitResult(card, config));
            Object result1 = factory.create();
            Object result2 = factory.create();

            assertThat(result1).isNotSameAs(result2);
            mockedHarness.verify(() -> HarnessFactory.createDeepAgent(card, config, null), times(2));
        }
    }

    @Test
    void destroy_callsDeepAgentDestroy() {
        AgentCard card = AgentCard.builder().id("destroy-agent").name("Destroy Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent mockAgent = mock(DeepAgent.class);

        EdpAgentFactory factory = new EdpAgentFactory(createInitResult(card, config));
        factory.destroy(mockAgent);

        verify(mockAgent).destroy();
    }

    @Test
    void destroy_nonDeepAgent_doesNothing() {
        AgentCard card = AgentCard.builder().id("noop-agent").name("Noop Agent").build();
        DeepAgentConfig config = new DeepAgentConfig();

        EdpAgentFactory factory = new EdpAgentFactory(createInitResult(card, config));
        factory.destroy(new Object());
    }
}
