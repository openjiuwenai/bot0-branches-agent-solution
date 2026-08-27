/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parity tests vs Python {@code flow_agent.py} / {@code test_flow_agent*.py} (stub ReAct).
 *
 * @since 2026-08-26
 */
class FlowAgentParityTest {
    private AtomicReference<Map<String, Object>> lastInputs;

    @BeforeEach
    void setUp() {
        lastInputs = new AtomicReference<>();
        StudioEngineTestSupport.installFlowAgent(new FlowAgentEngine.ReactBridge() {
            @Override
            public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                lastInputs.set(new LinkedHashMap<>(mappedInputs));
                return Map.of("output", "Agent response", "result_type", "answer");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> mappedInputs) {
                lastInputs.set(new LinkedHashMap<>(mappedInputs));
                return List.<Object>of(Map.of("type", "answer", "payload", Map.of("output", "streamed"))).iterator();
            }
        });
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }

    private static Map<String, Object> agentConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("strategy_name", "ReAct");
        conf.put("max_iteration", 5);
        conf.put("streaming", false);
        conf.put("system_prompt", "You are a helpful assistant");
        conf.put(
                "llm_config",
                Map.of(
                        "model_name",
                        "gpt-4",
                        "model_provider",
                        "openai",
                        "api_key",
                        "test",
                        "api_base",
                        "http://localhost"));
        return conf;
    }

    @Nested
    class ConfigAndInvoke {
        @Test
        void init_validatesAndMapsQuery() {
            FlowAgentEngine engine = StudioEngineTestSupport.createFlowAgent("a1", agentConf());
            Map<String, Object> out =
                    engine.invoke(Map.of("userFields", Map.of("query", "What's the weather today?")), null, null);
            assertThat(out).containsKey("userFields");
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf).containsEntry("output", "Agent response");
            assertThat(uf).containsEntry("result_type", "answer");
            assertThat(lastInputs.get()).containsEntry("query", "What's the weather today?");
        }

        @Test
        void mapFirstFieldToQueryWhenQueryAbsent() {
            FlowAgentEngine engine = StudioEngineTestSupport.createFlowAgent("a1", agentConf());
            engine.invoke(Map.of("userFields", Map.of("question", "hello")), null, null);
            assertThat(lastInputs.get()).containsEntry("query", "hello");
        }

        @Test
        void unsupportedStrategyFails() {
            Map<String, Object> conf = agentConf();
            conf.put("strategy_name", "PlanExecute");
            assertThatThrownBy(() -> new FlowAgentEngine("a1").init(conf))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("only ReAct");
        }

        @Test
        void streamYieldsChunks() {
            FlowAgentEngine engine = StudioEngineTestSupport.createFlowAgent("a1", agentConf());
            Iterator<Object> it = engine.stream(Map.of("userFields", Map.of("query", "hi")), null, null);
            assertThat(it.hasNext()).isTrue();
            assertThat(it.next()).isInstanceOf(Map.class);
        }

        @Test
        void outputDictExtractsDataResult() {
            StudioEngineTestSupport.clear();
            StudioEngineTestSupport.installFlowAgent(new FlowAgentEngine.ReactBridge() {
                @Override
                public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                    return Map.of(
                            "output",
                            Map.of("data", Map.of("result", "nested-ok")),
                            "result_type",
                            "answer");
                }

                @Override
                public Iterator<Object> stream(Map<String, Object> mappedInputs) {
                    return List.of().iterator();
                }
            });
            FlowAgentEngine engine = StudioEngineTestSupport.createFlowAgent("a1", agentConf());
            @SuppressWarnings("unchecked")
            Map<String, Object> uf =
                    (Map<String, Object>) engine.invoke(Map.of("userFields", Map.of("query", "q")), null, null)
                            .get("userFields");
            assertThat(uf).containsEntry("output", "nested-ok");
        }
    }
}
