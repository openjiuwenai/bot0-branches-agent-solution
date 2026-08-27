/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of mock paths from {@code test_flow_agent.py} via {@link FlowAgentEngine}.
 *
 * @since 2026-08-26
 */

class WorkflowNodeAgentCasesTest {
    private NodeTypeRegistry registry;
    private AtomicReference<Map<String, Object>> lastInputs;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        lastInputs = new AtomicReference<>();
        StudioEngineTestSupport.installFlowAgent(new FlowAgentEngine.ReactBridge() {
            @Override
            public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                lastInputs.set(new LinkedHashMap<>(mappedInputs));
                return Map.of("output", "Agent response", "result_type", "answer");
            }

            /**
             * stream.
             *
             * @param mappedInputs mappedInputs
             * @return result
             * @since 0.1.0
             */

            @Override
            public Iterator<Object> stream(Map<String, Object> mappedInputs) {
                return List.<Object>of(Map.of("output", "stream")).iterator();
            }
        });
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    private static Map<String, Object> agentConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("strategy_name", "ReAct");
        conf.put("max_iteration", 5);
        conf.put("system_prompt", "You are a helpful assistant");
        conf.put("llm_config", Map.of("model_name", "gpt-4", "model_provider", "openai"));
        return conf;
    }

    @Nested
    class HandlerPath {
        @Test
        void invokePutsOutputInUserFields() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agent", "jiuwen.agent", agentConf()), StudioEngineTestSupport.context("wf"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of("query", "What's the weather today?")), null, null));
            assertThat(fields).containsEntry("output", "Agent response");
            assertThat(lastInputs.get()).containsEntry("query", "What's the weather today?");
        }

        @Test
        void aliasFlowAgent() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("a", "jiuwen.flowAgent", agentConf()), StudioEngineTestSupport.context("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("query", "hi")), null, null)))
                    .containsEntry("output", "Agent response");
        }

        @Test
        void invalidStrategySurfaces() {
            StudioEngineTestSupport.clear();
            Map<String, Object> conf = agentConf();
            conf.put("strategy_name", "Other");
            ComponentExecutable exec =
                    registry.create(AssembledNode.of("a", "jiuwen.agent", conf), StudioEngineTestSupport.context("wf"));
            assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of("query", "q")), null, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("ReAct");
        }

        @Test
        void reactErrorSurfacesAsErrorPayload() {
            StudioEngineTestSupport.clear();
            StudioEngineTestSupport.installFlowAgent(new FlowAgentEngine.ReactBridge() {
                @Override
                public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                    throw new IllegalStateException("boom");
                }

                /**
                 * stream.
                 *
                 * @param mappedInputs mappedInputs
                 * @return result
                 * @since 0.1.0
                 */

                @Override
                public Iterator<Object> stream(Map<String, Object> mappedInputs) {
                    return List.of().iterator();
                }
            });
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agent", "jiuwen.agent", agentConf()), StudioEngineTestSupport.context("wf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> out =
                    (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of("query", "q")), null, null);
            assertThat(out).containsEntry("result_type", "error");
            assertThat(String.valueOf(out.get("output"))).contains("boom");
        }
    }
}
