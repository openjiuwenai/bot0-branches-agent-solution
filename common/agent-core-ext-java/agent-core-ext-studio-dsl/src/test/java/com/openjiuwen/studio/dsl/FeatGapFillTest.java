package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.bridge.DefaultMcpToolInvoker;
import com.openjiuwen.studio.dsl.bridge.InMemoryAgentRegistry;
import com.openjiuwen.studio.dsl.bridge.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatGapFillTest {

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
                new CodeLogicRegistry(),
                c -> null,
                new com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                tools,
                new InMemoryAgentRegistry(),
                registry);
        ComponentExecutable exec =
                registry.create(AssembledNode.of("p1", "jiuwen.plugin", Map.of("apiId", "api-1")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("q", "hi")), mock(NodeSessionApi.class), mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("pluginOk", true).containsEntry("echo", "hi");
    }

    @Test
    void mcp_registeredTool_invokes() {
        DefaultMcpToolInvoker mcp = new DefaultMcpToolInvoker();
        mcp.register("s1", "echo", args -> Map.of("out", args.get("v")));
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                new CodeLogicRegistry(),
                c -> null,
                new com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory(),
                mcp,
                null,
                new InMemoryToolRegistry(),
                new InMemoryAgentRegistry(),
                registry);
        ComponentExecutable exec = registry.create(
                AssembledNode.of("m1", "jiuwen.mcp", Map.of("server", "s1", "tool", "echo", "arguments", Map.of("v", 7))),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("out", 7);
    }

    @Test
    void agent_registered_invokes() {
        InMemoryAgentRegistry agents = new InMemoryAgentRegistry();
        agents.register("agent-a", in -> Map.of("reply", "ok-" + in.get("q")));
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                new CodeLogicRegistry(),
                c -> null,
                new com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                new InMemoryToolRegistry(),
                agents,
                registry);
        ComponentExecutable exec =
                registry.create(AssembledNode.of("a1", "jiuwen.agent", Map.of("agentId", "agent-a")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("q", "1")), mock(NodeSessionApi.class), mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("reply", "ok-1");
    }

    @Test
    void questioner_interact_hangsThenAnswers() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.interact(any())).thenReturn(Map.of("answer", "Kayla"));
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("q1", "jiuwen.questioner", Map.of("question", "name?")),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("answer", "Kayla").containsEntry("hangState", "Continue");
        verify(session).interact(any());
    }

    @Test
    void message_writesCustomStream() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("m1", "jiuwen.message", Map.of("message", "hi")), NodeBuildContext.defaults("wf"));
        exec.invoke(Map.of("userFields", Map.of()), session, mock(ModelContext.class));
        verify(session).writeCustomStream(any());
    }

    @Test
    void llm_consumesMediaParts() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        MediaPart img = new MediaPart("image", "image/png", "file:///a.png", null, Map.of());
        ComponentExecutable exec = registry.create(
                AssembledNode.of("llm1", "jiuwen.LLMComponent", Map.of("mockOutput", "seen")),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("q", "x"), "__media__", List.of(img)),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("mediaConsumed", true).containsKey("__mediaParts__");
    }

    @Test
    void coreFactory_buildsLlmWhenModelWired() {
        var factory = new com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory();
        ComponentExecutable core = factory.createLlm(AssembledNode.of(
                "n",
                "jiuwen.LLMComponent",
                Map.of(
                        "model", "gpt-test",
                        "apiKey", "k",
                        "apiBase", "http://localhost:9",
                        "prompt", "hi")));
        assertThat(core).isNotNull();
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
    void coreFactory_buildsKnowledgeIntentExtractor() {
        var factory = new com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory();
        assertThat(factory.createKnowledgeRetrieval(AssembledNode.of(
                        "k",
                        "jiuwen.knowledgeRetrieval",
                        Map.of("kbId", "kb-1", "topK", 3))))
                .isNotNull();
        assertThat(factory.createIntentDetection(AssembledNode.of(
                        "i",
                        "jiuwen.intentDetection",
                        Map.of(
                                "model",
                                "m",
                                "apiKey",
                                "k",
                                "apiBase",
                                "http://localhost:9",
                                "intents",
                                List.of(Map.of("intentId", "greet"))))))
                .isNotNull();
        assertThat(factory.createExtractor(AssembledNode.of(
                        "e",
                        "jiuwen.extractor",
                        Map.of(
                                "model",
                                "m",
                                "apiKey",
                                "k",
                                "apiBase",
                                "http://localhost:9",
                                "fields",
                                List.of("name")))))
                .isNotNull();
    }

    @Test
    void llm_buildsLlmContentPartsForMedia() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        MediaPart img = new MediaPart("image", "image/png", "file:///a.png", null, Map.of());
        ComponentExecutable exec = registry.create(
                AssembledNode.of("llm1", "jiuwen.LLMComponent", Map.of("mockOutput", "seen")),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("query", "describe"), "__media__", List.of(img)),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("mediaConsumed", true).containsKey("__llmContentParts__");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) uf.get("__llmContentParts__");
        assertThat(parts.stream().anyMatch(p -> "image_url".equals(p.get("type")))).isTrue();
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
                new CodeLogicRegistry(),
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
