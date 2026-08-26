/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity tests vs Python {@code flow_qa.py}.
 *
 * @since 2026-08-26
 */
class FlowQaParityTest {
    private final NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    @Nested
    class Strategy {
        @Test
        void index_picksOption() {
            FlowQaEngine engine = FlowQaEngine.fromConfigs(
                    "q",
                    Map.of(
                            "needReply",
                            false,
                            "qaStrategy",
                            "index",
                            "options",
                            List.of("A", "B", "C"),
                            "index_key",
                            "index"));
            Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of("index", 1)), null, null);
            assertThat(uf(out)).containsEntry("response", "B");
        }

        @Test
        void unsupportedStrategyFails() {
            FlowQaEngine engine =
                    FlowQaEngine.fromConfigs("q", Map.of("needReply", false, "qaStrategy", "round", "options", List.of("A")));
            assertThatThrownBy(() -> engine.invoke(Map.of("userFields", Map.of()), null, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("Unsupported QA strategy");
        }

        @Test
        void missingPlaceholderKeyFails() {
            FlowQaEngine engine = FlowQaEngine.fromConfigs(
                    "q", Map.of("needReply", false, "qaStrategy", "index", "options", List.of("hi {{name}}")));
            assertThatThrownBy(() -> engine.invoke(Map.of("userFields", Map.of("index", 0)), null, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("QA parameter key not found: name");
        }

        @Test
        void placeholderRenders() {
            FlowQaEngine engine = FlowQaEngine.fromConfigs(
                    "q",
                    Map.of(
                            "needReply",
                            false,
                            "qaStrategy",
                            "index",
                            "options",
                            List.of("hi {{name}}"),
                            "index_key",
                            "index"));
            assertThat(uf(engine.invoke(Map.of("userFields", Map.of("index", 0, "name", "Kayla")), null, null)))
                    .containsEntry("response", "hi Kayla");
        }
    }

    @Nested
    class StructSchema {
        @Test
        void normalizeFillsMissingObjectFields() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put(
                    "properties",
                    Map.of(
                            "amount",
                            Map.of("default", ""),
                            "currency",
                            Map.of("default", "CNY")));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>)
                    StructInputSchemas.normalizeWithSchema(Map.of("amount", "10"), schema);
            assertThat(normalized).containsEntry("amount", "10").containsEntry("currency", "CNY");
            // schema order: amount then currency (LinkedHashMap from Map.of may not preserve — check keys)
            assertThat(normalized.keySet()).containsExactlyInAnyOrder("amount", "currency");
        }

        @Test
        void structMessageUsesNormalizedInputs() {
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("needReply", false);
            conf.put("qaStrategy", "index");
            conf.put("options", List.of("ask"));
            conf.put("isStructMessage", true);
            conf.put("struct_output_template", "amt={{confirmData}}");
            conf.put(
                    "struct_input_schemas",
                    Map.of(
                            "confirmData",
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("amount", Map.of("default", ""), "unit", Map.of("default", "yuan")))));
            FlowQaEngine engine = FlowQaEngine.fromConfigs("q", conf);
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> ufIn = new LinkedHashMap<>();
            ufIn.put("index", 0);
            ufIn.put("confirmData", Map.of("amount", "9"));
            Map<String, Object> out = engine.invoke(Map.of("userFields", ufIn), session, null);
            assertThat(uf(out)).containsEntry("response", "ask");
        }
    }

    @Nested
    class Handler {
        @Test
        void needReply_hangsThenResumes() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "q",
                            "EI.qa",
                            Map.of("needReply", true, "options", List.of("hello"), "qaStrategy", "index")),
                    NodeBuildContext.defaults("wf"));
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getState(any())).thenReturn(null);
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of()), session, null)))
                    .containsEntry("hangState", "INPUT_REQUIRED")
                    .containsEntry("response", "hello");

            when(session.getState(any()))
                    .thenReturn(Map.of("status", "user_interact", "question", "hello", "inputs", Map.of()));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("userReply", "yes")), session, null)))
                    .containsEntry("response", "yes");
        }

        @Test
        void alias_flowQa() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "q",
                            "jiuwen.flowQa",
                            Map.of("needReply", false, "qaStrategy", "index", "options", List.of("X"))),
                    NodeBuildContext.defaults("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("index", 0)), null, null)))
                    .containsEntry("response", "X");
        }
    }
}
