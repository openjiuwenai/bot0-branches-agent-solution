/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock ports for Python workflow_node cases that originally required real LLM / HTTP
 * ({@code test_llm_chain.py}, {@code test_intent_detection_real_llm.py},
 * {@code test_flow_card_use_real_llm.py}, {@code test_case_plugin_multi_level_params_01.py}).
 * Real-network integration is OUT of FEAT-031 acceptance.
 *
 * @since 2026-08-26
 */
class WorkflowNodeLlmMockCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        Object user = out.get("userFields");
        if (user instanceof Map<?, ?>) {
            return (Map<String, Object>) user;
        }
        return out;
    }

    private static Map<String, Object> llmConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put(
                "model",
                Map.of(
                        "modelName",
                        "gpt-test",
                        "modelType",
                        "OpenAI",
                        "extension",
                        Map.of("api_key", "k", "api_base", "http://localhost")));
        conf.put("deployMode", "cloud");
        conf.put("templateContent", List.of(Map.of("role", "user", "content", "{{query}}")));
        conf.put("responseFormat", Map.of("type", "text"));
        conf.put("enableHistory", false);
        conf.put(
                "userFields",
                Map.of("outputs", List.of(Map.of("id", "raw_output", "type", "string"))));
        return conf;
    }

    @Test
    void test_workflow_llm_text_invoke_mock() {
        LlmChainEngine.installTestBridge(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("hello from mock llm");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        try {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("llm", "jiuwen.llm", llmConf()),
                    NodeBuildContext.defaults("wf_llm"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of("query", "hi")), null, null));
            assertThat(fields).containsEntry("raw_output", "hello from mock llm");
        } finally {
            LlmChainEngine.clearTestBridge();
        }
    }

    @Test
    void test_workflow_llm_chain_two_nodes_mock() {
        LlmChainEngine.installTestBridge(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("step2-final");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        try {
            Map<String, Object> conf = llmConf();
            conf.put("templateContent", List.of(Map.of("role", "user", "content", "continue")));
            AssembledWorkflow wf = new AssembledWorkflow(
                    "llm_chain",
                    List.of(
                            AssembledNode.of("start", "jiuwen.start", Map.of()),
                            AssembledNode.of("llm1", "jiuwen.llm", conf),
                            AssembledNode.of("llm2", "jiuwen.llm", conf),
                            AssembledNode.of(
                                    "end",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "{{raw_output}}"))));
            Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                            registry,
                            wf,
                            NodeBuildContext.defaults("llm_chain"),
                            Map.of("userFields", Map.of("query", "chain")),
                            mock(NodeSessionApi.class),
                            null);
            assertThat(String.valueOf(uf(out).get("answer"))).contains("step2-final");
        } finally {
            LlmChainEngine.clearTestBridge();
        }
    }

    @Test
    void test_workflow_intent_detection_weather_mock() {
        IntentDetectionEngine.installTestInvoker(messages -> "{\"class\": \"分类1\", \"reason\": \"询问天气\"}");
        try {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "intent",
                            "jiuwen.intentDetection",
                            Map.of(
                                    "llm",
                                    Map.of(
                                            "model",
                                            Map.of(
                                                    "modelName",
                                                    "gpt-test",
                                                    "modelType",
                                                    "OpenAI",
                                                    "extension",
                                                    Map.of("api_key", "k", "api_base", "http://localhost"))),
                                    "branches",
                                    List.of(
                                            Map.of("id", "branch_0", "catalog", "其他意图"),
                                            Map.of("id", "branch_1", "catalog", "天气查询")),
                                    "enableHistory",
                                    true,
                                    "enableInput",
                                    true)),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("input", "今天天气怎么样"), null, null));
            assertThat(fields.get("classificationId")).isEqualTo(1);
            assertThat(String.valueOf(fields.get("name"))).contains("天气");
        } finally {
            IntentDetectionEngine.clearTestInvoker();
        }
    }

    @Test
    void test_complete_workflow_from_ir_card_mock() {
        ComponentExecutable card = registry.create(
                AssembledNode.of(
                        "card",
                        "jiuwen.card",
                        Map.of("template", "{\"title\":\"{{title}}\"}", "output_mode", "separate")),
                NodeBuildContext.defaults("wf_card"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) card.invoke(
                Map.of("userFields", Map.of("title", "mock-card")), mock(NodeSessionApi.class), null);
        assertThat(out).containsOnlyKeys("result");
        assertThat(String.valueOf(out.get("result"))).contains("mock-card");
    }

    @Test
    void test_flow_api_multi_level_params_mock() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        Map<String, Object> nested = Map.of(
                "content",
                "ok",
                "param",
                "p1",
                "ext",
                Map.of("abc", 1, "string_text", "hello"));
        ToolCard toolCard =
                ToolCard.builder().id("mock_plugin_multi_level_param").name("multi").description("m").build();
        tools.register("mock_plugin_multi_level_param", new Tool(toolCard) {
            @Override
            public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return Map.of("errCode", 0, "errMessage", "success", "data", nested);
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return List.of().iterator();
            }
        });
        NodeBuildContext ctx = new NodeBuildContext(
                "wf_multi",
                0,
                5,
                null,
                c -> null,
                tools,
                registry);
        AssembledWorkflow wf = new AssembledWorkflow(
                "wf_multi",
                List.of(
                        AssembledNode.of("node_start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "node_plugin",
                                "jiuwen.plugin",
                                Map.of(
                                        "apiId",
                                        "mock_plugin_multi_level_param",
                                        "name",
                                        "mock_plugin_multi_level_param",
                                        "response",
                                        List.of(
                                                Map.of("name", "content"),
                                                Map.of("name", "param"),
                                                Map.of("name", "ext")))),
                        AssembledNode.of(
                                "node_end",
                                "jiuwen.end",
                                Map.of("responseTemplate", "{{content}}"))));
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                registry, wf, ctx, Map.of("userFields", Map.of("query", "x")), mock(NodeSessionApi.class), null);
        Map<String, Object> fields = uf(out);
        assertThat(fields).containsEntry("content", "ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) fields.get("ext");
        assertThat(ext).containsEntry("abc", 1);
    }
}
