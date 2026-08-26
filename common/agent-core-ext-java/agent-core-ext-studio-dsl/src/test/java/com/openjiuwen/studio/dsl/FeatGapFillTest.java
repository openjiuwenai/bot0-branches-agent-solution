/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FeatGapFillTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class FeatGapFillTest {
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
    void loop_executesLoopBodyNodes() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "loop1",
                        "jiuwen.loop",
                        Map.of(
                                "maxIterations",
                                2,
                                "loopBody",
                                List.of(Map.of(
                                        "id",
                                        "sv",
                                        "type",
                                        "jiuwen.setVariable",
                                        "configs",
                                        Map.of("variableMapping", Map.of("tick", 1)))))),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("x", 0)), mock(NodeSessionApi.class), mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("loopCount")).isEqualTo(2);
        assertThat(uf).containsEntry("tick", 1);
    }

    @Test
    void plugin_apiId_usesToolRegistry() throws Exception {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        ToolCard card = ToolCard.builder().id("api-1").name("api-1").description("test").build();
        tools.register("api-1", new Tool(card) {
            @Override
            public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return Map.of("pluginOk", true, "echo", inputs.get("q"));
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return List.of().iterator();
            }
        });
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                c -> null,
                tools,
                registry);
        ComponentExecutable exec =
                registry.create(AssembledNode.of("p1", "jiuwen.plugin", Map.of("apiId", "api-1")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(
                        Map.of("userFields", Map.of("q", "hi")),
                        mock(NodeSessionApi.class),
                        mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("pluginOk", true).containsEntry("echo", "hi");
    }

    @Test
    void mcp_stubClient_invokes() {
        com.openjiuwen.studio.dsl.flowmcp.FlowMcpEngine.installTestClient(
                com.openjiuwen.studio.dsl.flowmcp.RecordingMcpClient.withContent(
                        List.of(Map.of("type", "text", "text", "echo-7"))));
        try {
            NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("type", "sse");
            conf.put("url", "http://localhost/mcp");
            conf.put("name", "s1");
            conf.put("tool_name", "mock_tool");
            conf.put(
                    "arguments",
                    List.of(Map.of(
                            "name",
                            "query",
                            "description",
                            "q",
                            "type",
                            "string",
                            "required",
                            true,
                            "method",
                            "Body")));
            ComponentExecutable exec =
                    registry.create(AssembledNode.of("m1", "jiuwen.mcp", conf), NodeBuildContext.defaults("wf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    exec.invoke(Map.of("userFields", Map.of("query", "7")), null, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf).containsEntry("isError", false).containsKey("content");
        } finally {
            com.openjiuwen.studio.dsl.flowmcp.FlowMcpEngine.clearTestClient();
        }
    }

    @Test
    void agent_reactBridge_invokes() {
        com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine.installTestBridge(
                new com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine.ReactBridge() {
                    @Override
                    public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                        return Map.of("output", "ok-" + mappedInputs.get("query"), "result_type", "answer");
                    }

                    @Override
                    public java.util.Iterator<Object> stream(Map<String, Object> mappedInputs) {
                        return List.of().iterator();
                    }
                });
        try {
            NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("strategy_name", "ReAct");
            conf.put("max_iteration", 3);
            conf.put("llm_config", Map.of("model_name", "m"));
            ComponentExecutable exec =
                    registry.create(AssembledNode.of("a1", "jiuwen.agent", conf), NodeBuildContext.defaults("wf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    exec.invoke(Map.of("userFields", Map.of("query", "1")), null, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf).containsEntry("output", "ok-1");
        } finally {
            com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine.clearTestBridge();
        }
    }

    @Test
    void questioner_interact_hangsThenAnswers() {
        AtomicReference<Map<String, Object>> stateBucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return stateBucket.get();
            }
            return stateBucket.get().get(String.valueOf(key));
        });
        doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    Map<String, Object> cur = new HashMap<>(stateBucket.get());
                    cur.putAll(patch);
                    stateBucket.set(cur);
                    return null;
                })
                .when(session)
                .updateState(any());
        when(session.interact(any())).thenReturn(Map.of("answer", "Kayla"));

        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("q1", "jiuwen.questioner", Map.of("question", "name?")),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hang =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> hangUf = (Map<String, Object>) hang.get("userFields");
        assertThat(hangUf.get("hangState")).isEqualTo("INPUT_REQUIRED");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("answer", "Kayla")), session, mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("answer", "Kayla");
    }

    @Test
    void message_writesCustomStream() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("m1", "jiuwen.message", Map.of("template", "hi")), NodeBuildContext.defaults("wf"));
        exec.invoke(Map.of("userFields", Map.of()), session, mock(ModelContext.class));
        verify(session, atLeastOnce()).writeCustomStream(any());
    }

    @Test
    void llm_invokeWithStubBridge() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        LlmChainEngine.installTestBridge(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("seen");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        try {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("llm1", "jiuwen.LLMComponent", llmConf()),
                    NodeBuildContext.defaults("wf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) exec.invoke(
                    Map.of("userFields", Map.of("query", "x")),
                    mock(NodeSessionApi.class),
                    mock(ModelContext.class));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf.get("raw_output")).isEqualTo("seen");
        } finally {
            LlmChainEngine.clearTestBridge();
        }
    }


    @Test
    void branch_selectsAndExposesBranchId() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "b1",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "yes",
                                                "condition",
                                                Map.of("variable", "flag", "operator", "eq", "value", true)),
                                        Map.of("branchId", "default", "isDefault", true)))),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("flag", true)), mock(NodeSessionApi.class), mock(ModelContext.class));
        assertThat(out.get("branchId")).isEqualTo("yes");
    }


    @Test
    void llm_visionInjectsImageIntoLastUserMessage() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        LlmChainEngine.installTestBridge(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                captured.set(messages);
                return new AssistantMessage("ok");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        try {
            Map<String, Object> conf = llmConf();
            Map<String, Object> model = new LinkedHashMap<>(
                    (Map<String, Object>) conf.get("model"));
            Map<String, Object> extension = new LinkedHashMap<>(
                    (Map<String, Object>) model.get("extension"));
            extension.put("vl_enable", true);
            model.put("extension", extension);
            conf.put("model", model);

            ComponentExecutable exec = registry.create(
                    AssembledNode.of("llm1", "jiuwen.LLMComponent", conf),
                    NodeBuildContext.defaults("wf"));
            exec.invoke(
                    Map.of(
                            "userFields",
                            Map.of("query", "describe", "image_vision", "data:image/png;base64,aaa")),
                    mock(NodeSessionApi.class),
                    mock(ModelContext.class));
            Object content = captured.get().get(captured.get().size() - 1).getContent();
            assertThat(content).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content;
            assertThat(parts.stream().anyMatch(p -> "image_url".equals(p.get("type")))).isTrue();
        } finally {
            LlmChainEngine.clearTestBridge();
        }
    }

    @Test
    void nested_restoresParentSessionState() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeSessionApi session = mock(NodeSessionApi.class);
        java.util.LinkedHashMap<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("parentOnly", "keep");
        when(session.dumpState()).thenAnswer(inv -> new java.util.LinkedHashMap<>(state));
        org.mockito.Mockito.doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = (Map<String, Object>) inv.getArgument(0);
                    patch.forEach((k, v) -> {
                        if (v == null) {
                            state.remove(k);
                        } else {
                            state.put(k, v);
                        }
                    });
                    return null;
                })
                .when(session)
                .updateState(any());
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                configs -> new com.openjiuwen.studio.dsl.model.AssembledWorkflow(
                        "child",
                        List.of(
                                AssembledNode.of(
                                        "sv",
                                        "jiuwen.setVariable",
                                        Map.of("variableMapping", Map.of("childLeak", 1))),
                                AssembledNode.of("e", "jiuwen.end", Map.of()))));
        ComponentExecutable exec =
                registry.create(AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child")), ctx);
        exec.invoke(Map.of("userFields", Map.of("q", "x")), session, mock(ModelContext.class));
        assertThat(state).containsEntry("parentOnly", "keep").doesNotContainKey("childLeak");
    }

    @Test
    void defaults_respectsMaxNestingDepthProperty() {
        var props = new com.openjiuwen.studio.dsl.config.StudioDslNodeProperties();
        props.setMaxNestingDepth(2);
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        assertThat(ctx.maxNestingDepth()).isEqualTo(2);
    }
}
