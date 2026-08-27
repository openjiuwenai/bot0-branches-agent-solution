/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.complexintent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity vs Python {@code complex_intent_detection.py}.
 *
 * @since 2026-08-26
 */

class ComplexIntentDetectionParityTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        StudioEngineTestSupport.clear();
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object out) {
        return (Map<String, Object>) ((Map<String, Object>) out).get("userFields");
    }

    private static Map<String, Object> baseConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("intentDetectionContainerNodeId", "intent_box");
        conf.put(
                "branches",
                List.of(
                        Map.of("id", "branch_0", "catalog", "其他意图"),
                        Map.of(
                                "id",
                                "branch_1",
                                "catalog",
                                "refund",
                                "configs",
                                Map.of("workflow_id", "wf_refund"))));
        conf.put("groups", Map.of("reply", List.of("${branch_1.userFields.answer}")));
        return conf;
    }

    @Nested
    class Config {
        @Test
        void emptyBranchesFails() {
            assertThatThrownBy(() -> ComplexIntentDetectionConfig.from("c", Map.of("groups", Map.of("a", List.of()))))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("branches");
        }

        @Test
        void emptyGroupsFails() {
            assertThatThrownBy(() ->
                            ComplexIntentDetectionConfig.from(
                                    "c", Map.of("branches", List.of(Map.of("id", "branch_0", "catalog", "x")))))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("groups");
        }
    }

    @Nested
    class InvokeWithStub {
        @Test
        void intentOnly_whenBranchHasNoWorkflow() {
            StudioEngineTestSupport.installComplexIntent(new ComplexIntentDetectionEngine.TestBridge() {
                @Override
                public Map<String, Object> intentResult(Map<String, Object> convertedInputs) {
            return Map.of("classificationId", 0, "result", "分类0", "name", "其他意图", "reason", "");
        }
                @Override
                public Map<String, Object> subWorkflowResult(String workflowId, Map<String, Object> subInputs) {
                    return Map.of();
                }
            });
            Map<String, Object> conf = baseConf();
            // branch_0 has no configs.workflow_id → intent only
            ComplexIntentDetectionEngine engine = StudioEngineTestSupport.createComplexIntent("c1", conf);
            Map<String, Object> fields =
                    uf(engine.invoke(Map.of("userFields", Map.of("input", "hello")), null, null));
            assertThat(fields).containsEntry("classificationId", 0).containsEntry("name", "其他意图");
        }

        @Test
        void branchWorkflow_aggregatesGroup() {
            StudioEngineTestSupport.installComplexIntent(new ComplexIntentDetectionEngine.TestBridge() {
                @Override
                public Map<String, Object> intentResult(Map<String, Object> convertedInputs) {
            return Map.of("classificationId", 1, "result", "分类1", "name", "refund", "reason", "llm");
        }
                @Override
                public Map<String, Object> subWorkflowResult(String workflowId, Map<String, Object> subInputs) {
                    assertThat(workflowId).isEqualTo("wf_refund");
                    return Map.of("userFields", Map.of("answer", "refund-ok"), "responseContent", "refund-ok");
                }
            });
            ComplexIntentDetectionEngine engine = StudioEngineTestSupport.createComplexIntent("c1", baseConf());
            Map<String, Object> fields =
                    uf(engine.invoke(Map.of("userFields", Map.of("input", "I want a refund")), null, null));
            assertThat(fields).containsEntry("classificationId", 1).containsEntry("reply", "refund-ok");
            assertThat(fields.get("responseContent")).isEqualTo("refund-ok");
        }

        @Test
        void convertInput_pullsFromUserFields() {
            Map<String, Object> converted =
                    ComplexIntentDetectionEngine.convertInput(Map.of("userFields", Map.of("input", "q1")));
            assertThat(converted).containsEntry("input", "q1");
        }

        @Test
        void parseValue_nestedPath() {
            Object v = ComplexIntentDetectionEngine.parseValue(
                    Map.of("userFields", Map.of("answer", "A")), List.of("userFields", "answer"));
            assertThat(v).isEqualTo("A");
        }
    }

    @Nested
    class HandlerPath {
        @Test
        void handlerUsesEngineStub() {
            StudioEngineTestSupport.installComplexIntent(new ComplexIntentDetectionEngine.TestBridge() {
                @Override
                public Map<String, Object> intentResult(Map<String, Object> convertedInputs) {
            return Map.of("classificationId", 1, "name", "refund");
        }
                @Override
                public Map<String, Object> subWorkflowResult(String workflowId, Map<String, Object> subInputs) {
                    return Map.of("userFields", Map.of("answer", "done"));
                }
            });
            ComponentExecutable exec =
                    registry.create(AssembledNode.of("c", "EI.ComplexIntentDetection", baseConf()), StudioEngineTestSupport.context("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("input", "refund please")), null, null)))
                    .containsEntry("classificationId", 1)
                    .containsEntry("reply", "done");
        }

        @Test
        void resolverPath_runsChildLinear() {
            StudioEngineTestSupport.clear();
            // Force intent via test invoker on IntentDetection would need model; use bridge for intent only
            // and real resolver for child — hybrid: bridge returns intent, null sub → then real sub if bridge
            // returns null for sub... Our bridge always used for both when set.
            // So: no bridge; install IntentDetection test invoker.
            StudioEngineTestSupport.installIntent(
                    messages -> "分类1");
            try {
                AssembledWorkflow child = new AssembledWorkflow(
                        "wf_refund",
                        List.of(
                                AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "child-done"))));
                NodeBuildContext ctx =
                        StudioEngineTestSupport.withCurrentOverrides(
                                new NodeBuildContext(
                                        "parent",
                                        0,
                                        5,
                                        null,
                                        configs -> child,
                                        new com.openjiuwen.studio.dsl.support.InMemoryToolRegistry(),
                                        registry));
                Map<String, Object> conf = baseConf();
                // IntentDetection needs model wiring OR test invoker — installed above
                conf.put("llm", Map.of("model_name", "m"));
                ComponentExecutable exec =
                        registry.create(AssembledNode.of("c", "EI.ComplexIntentDetection", conf), ctx);
                Map<String, Object> fields =
                        uf(exec.invoke(Map.of("userFields", Map.of("input", "refund")), null, null));
                assertThat(fields).containsKey("classificationId");
            } finally {
                StudioEngineTestSupport.clear();
            }
        }
    }
}
