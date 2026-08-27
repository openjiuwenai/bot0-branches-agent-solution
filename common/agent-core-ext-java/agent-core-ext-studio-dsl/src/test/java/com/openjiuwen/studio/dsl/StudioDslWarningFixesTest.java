/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.flowend.FlowEndEngine;
import com.openjiuwen.studio.dsl.flowsetvariable.FlowSetVariableConfig;
import com.openjiuwen.studio.dsl.flowsetvariable.FlowSetVariableEngine;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine;
import com.openjiuwen.studio.dsl.kb.KBAdapterFactory;
import com.openjiuwen.studio.dsl.kb.KBSearchResult;
import com.openjiuwen.studio.dsl.kb.OpenJiuwenKBAdapter;
import com.openjiuwen.studio.dsl.kb.RagFlowAdapter;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.PythonCodeRunners;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;
import com.openjiuwen.studio.dsl.util.OutboundUrlSafety;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Regression tests for review Warning batch A fixes.
 *
 * @since 2026-08-27
 */
class StudioDslWarningFixesTest {
    @AfterEach
    void tearDown() {
        PythonCodeRunners.setSandboxExecutor(null);
        System.clearProperty("studio.dsl.sandbox.strict");
        StudioEngineTestSupport.clear();
    }

    @Test
    void conditionEvaluator_mapIn_checksMembershipNotSubstringReverse() {
        Map<String, Object> cond = Map.of(
                "operator", "in",
                "left", "b",
                "right", List.of("a", "b", "c"));
        assertThat(ConditionEvaluator.matches(cond, Map.of())).isTrue();

        Map<String, Object> reversed = Map.of(
                "operator", "in",
                "left", "abc",
                "right", "b");
        assertThat(ConditionEvaluator.matches(reversed, Map.of())).isFalse();
    }

    @Test
    void conditionEvaluator_containsDiffersFromIn() {
        assertThat(
                        ConditionEvaluator.matches(
                                Map.of("operator", "contains", "left", "abc", "right", "b"),
                                Map.of()))
                .isTrue();
        assertThat(
                        ConditionEvaluator.matches(
                                Map.of("operator", "in", "left", "abc", "right", "b"),
                                Map.of()))
                .isFalse();
    }

    @Test
    void branchSelect_withoutDefault_throwsConfigInvalid() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = StudioEngineTestSupport.context("wf");
        ComponentExecutable exec =
                registry.create(
                        AssembledNode.of(
                                "b1",
                                "jiuwen.branch",
                                Map.of(
                                        "branches",
                                        List.of(Map.of("branchId", "if", "condition", "false")))),
                        ctx);
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of("query", "x")), null, null))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(
                        e -> {
                            NodeExecutionException ne = (NodeExecutionException) e;
                            assertThat(ne.nodeId()).isEqualTo("b1");
                            assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
                        });
    }

    @Test
    void unknownType_create_usesNodeIdInFailureSurface() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = StudioEngineTestSupport.context("wf");
        AssembledNode node = AssembledNode.of("node-42", "EI.notAThing", Map.of());
        assertThatThrownBy(() -> registry.create(node, ctx))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(
                        e -> {
                            NodeExecutionException ne = (NodeExecutionException) e;
                            assertThat(ne.nodeId()).isEqualTo("node-42");
                            assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.UNKNOWN_NODE_TYPE);
                        });
    }

    @Test
    void setVariable_doesNotPersistEntireInputEnvelope() {
        WorkflowVariableScope scope = new WorkflowVariableScope();
        FlowSetVariableEngine engine =
                new FlowSetVariableEngine(
                        FlowSetVariableConfig.fromNodeConfigs(
                                Map.of("variableMapping", Map.of("assigned", "${ephemeral}"))),
                        scope,
                        "wf",
                        ConversationValsStores.memoryStore());
        engine.invoke(
                Map.of("userFields", Map.of("ephemeral", "secret", "other", "noise")),
                null);
        assertThat(scope.snapshot()).containsEntry("assigned", "secret");
        assertThat(scope.snapshot()).doesNotContainKeys("ephemeral", "other");
    }

    @Test
    void sandboxStrict_rejectsMissingExecutor() {
        System.setProperty("studio.dsl.sandbox.strict", "true");
        assertThatThrownBy(() -> PythonCodeRunners.resolve("sandbox", "subprocess", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void conversationValsStores_reusesJedisInstance() {
        ConversationValsStores.setDefault(ConversationValsStores.memoryStore());
        assertThat(ConversationValsStores.get()).isSameAs(ConversationValsStores.get());
    }

    @Test
    void flowEndEngine_streamFlagIsSessionScoped() {
        NodeSessionApi session1 = statefulSession();
        assertThat(FlowEndEngine.alreadyStreamed(session1, "end1")).isFalse();
        FlowEndEngine.markStreamed(session1, "end1");
        assertThat(FlowEndEngine.alreadyStreamed(session1, "end1")).isTrue();

        NodeSessionApi session2 = statefulSession();
        assertThat(FlowEndEngine.alreadyStreamed(session2, "end1")).isFalse();
    }

    @Test
    void endNode_streamIdempotentOnSameSession() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = StudioEngineTestSupport.context("wf");
        ComponentExecutable exec =
                registry.create(
                        AssembledNode.of(
                                "end1",
                                "jiuwen.end",
                                Map.of("responseTemplate", "Hello {{name}}!", "stream_output", true)),
                        ctx);
        Map<String, Object> inputs = Map.of("userFields", Map.of("name", "World"));
        NodeSessionApi session = statefulSession();
        assertThat(exec.stream(inputs, session, null).hasNext()).isTrue();
        assertThat(exec.stream(inputs, session, null).hasNext()).isFalse();
    }

    @Test
    void outboundUrlSafety_blocksPrivateNetwork() {
        System.setProperty("studio.dsl.outbound.allowPrivate", "false");
        try {
            assertThatThrownBy(() -> OutboundUrlSafety.validateOutbound("http://127.0.0.1/health"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OutboundUrlSafety.validateOutbound("http://10.0.0.1/api"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            System.setProperty("studio.dsl.outbound.allowPrivate", "true");
        }
    }

    @Test
    void typeCoercer_parsesJsonArrayViaJackson() {
        Map<String, Object> out =
                TypeCoercer.coerceMap(
                        Map.of("tags", "[\"a\", 1, true]"),
                        Map.of("tags", Map.of("type", "array")));
        assertThat(out.get("tags")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) out.get("tags");
        assertThat(tags).containsExactly("a", 1, true);
    }

    @Test
    void kbAdapterFactory_supportsOpenJiuwen() {
        assertThat(KBAdapterFactory.create("OpenJiuwen")).isInstanceOf(OpenJiuwenKBAdapter.class);
    }

    @Test
    void studioTestHooks_tracksInstallLifecycle() {
        assertThat(StudioEngineTestSupport.isActive()).isFalse();
        StudioEngineTestSupport.installLlm(
                new LlmChainEngine.ModelBridge() {
                    @Override
                    public com.openjiuwen.core.foundation.llm.schema.AssistantMessage invoke(
                            java.util.List<com.openjiuwen.core.foundation.llm.schema.BaseMessage> messages) {
                        return null;
                    }

                    @Override
                    public java.util.Iterator<com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk> stream(
                            java.util.List<com.openjiuwen.core.foundation.llm.schema.BaseMessage> messages) {
                        return java.util.List.<com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk>of()
                                .iterator();
                    }
                });
        assertThat(StudioEngineTestSupport.isActive()).isTrue();
        StudioEngineTestSupport.clear();
        assertThat(StudioEngineTestSupport.isActive()).isFalse();
    }

    @Test
    void ragFlowParseResponse_alignsSubtitleTypeAndFileIdFallback() {
        Map<String, Object> resp =
                Map.of(
                        "code",
                        0,
                        "data",
                        Map.of(
                                "chunks",
                                List.of(
                                        Map.of(
                                                "content",
                                                "faq answer",
                                                "similarity",
                                                0.95,
                                                "document_keyword",
                                                "FAQ Doc",
                                                "document_id",
                                                "",
                                                "chunk_id",
                                                "chunk-1",
                                                "dataset_id",
                                                "ds-1"))));
        List<KBSearchResult> hits = RagFlowAdapter.parseResponse(resp);
        assertThat(hits).hasSize(1);
        KBSearchResult hit = hits.get(0);
        assertThat(hit.subtitle()).isEqualTo("FAQ Doc");
        assertThat(hit.type()).isEqualTo("faq");
        assertThat(hit.fileId()).isEqualTo("chunk-1");
        assertThat(hit.knowledgeBaseId()).isEqualTo("ds-1");
    }

    @Test
    void flowAgentInvoke_preservesResultType() {
        StudioEngineTestSupport.installFlowAgent(
                new FlowAgentEngine.ReactBridge() {
                    @Override
                    public Map<String, Object> invoke(Map<String, Object> mappedInputs) {
                        return Map.of("output", "ok", "result_type", "tool");
                    }

                    @Override
                    public java.util.Iterator<Object> stream(Map<String, Object> mappedInputs) {
                        return List.<Object>of().iterator();
                    }
                });
        try {
            FlowAgentEngine engine =
                    StudioEngineTestSupport.createFlowAgent(
                            "a1", Map.of("strategy_name", "ReAct", "max_iteration", 1));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf =
                    (Map<String, Object>)
                            engine.invoke(Map.of("userFields", Map.of("query", "q")), null, null)
                                    .get("userFields");
            assertThat(uf).containsEntry("result_type", "tool");
        } finally {
            StudioEngineTestSupport.clear();
        }
    }

    @Test
    void llmChainRealTimeStream_mergesChunkMetadataIntoModelStats() {
        AssistantMessageChunk withMeta = new AssistantMessageChunk();
        withMeta.setContent("hi");
        withMeta.setMetadata(Map.of("latency_ms", 12));
        AssistantMessageChunk terminal = new AssistantMessageChunk();
        terminal.setContent("");
        terminal.setFinishReason("stop");

        StudioEngineTestSupport.installLlm(
                new LlmChainEngine.ModelBridge() {
                    @Override
                    public com.openjiuwen.core.foundation.llm.schema.AssistantMessage invoke(
                            List<com.openjiuwen.core.foundation.llm.schema.BaseMessage> messages) {
                        return null;
                    }

                    @Override
                    public java.util.Iterator<AssistantMessageChunk> stream(
                            List<com.openjiuwen.core.foundation.llm.schema.BaseMessage> messages) {
                        return List.of(withMeta, terminal).iterator();
                    }
                });
        try {
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put(
                    "model",
                    Map.of(
                            "modelName",
                            "gpt-test",
                            "modelType",
                            "OpenAI",
                            "hyperParameters",
                            Map.of("temperature", 0.1),
                            "extension",
                            Map.of("api_key", "k", "api_base", "http://localhost")));
            conf.put("deployMode", "cloud");
            conf.put(
                    "templateContent",
                    List.of(Map.of("role", "user", "content", "请回答：{{query}}")));
            conf.put("responseFormat", Map.of("type", "text"));
            conf.put("enableHistory", false);
            conf.put(
                    "userFields",
                    Map.of("inputs", List.of(), "outputs", List.of(Map.of("id", "raw_output", "type", "string"))));

            LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", conf);
            java.util.Iterator<Object> frames =
                    engine.stream(Map.of("userFields", Map.of("query", "hi")), null, null);
            while (frames.hasNext()) {
                frames.next();
            }
            assertThat(engine.getStreamOutput()).isNotNull();
            assertThat(engine.getStreamOutput().get("model_stats")).isEqualTo(Map.of("latency_ms", 12));
        } finally {
            StudioEngineTestSupport.clear();
        }
    }

    private static NodeSessionApi statefulSession() {
        AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = Mockito.mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return bucket.get();
            }
            return bucket.get().get(String.valueOf(key));
        });
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = inv.getArgument(0);
            Map<String, Object> cur = new LinkedHashMap<>(bucket.get());
            patch.forEach(cur::put);
            bucket.set(cur);
            return null;
        }).when(session).updateState(any());
        return session;
    }
}
