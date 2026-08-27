/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smoke tests for the documented host entry {@link StudioDslModule}: consume assembled products
 * (no DSL loader) via {@code mapExecutables} and test-only {@link LinearWorkflowTestSupport}.
 *
 * @since 2026-08-17
 */

class StudioDslModuleTest {
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
    void mapExecutables_linearRun_closesVariableScope() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "host-wf-1",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "v",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("greeting", "hello"))),
                        AssembledNode.of("m", "jiuwen.message", Map.of("template", "done")),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));

        NodeBuildContext ctx = module.newRootContext("host-wf-1", "tenant-host");
        WorkflowVariableScope scope = ctx.variableScope();

        assertThat(module.mapExecutables(wf, ctx)).hasSize(4);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                module.registry(),
                wf,
                ctx,
                Map.of("userFields", Map.of("seed", 1)),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("greeting", "hello").containsEntry("seed", 1);
        assertThat(scope.isClosed()).isTrue();
        assertThatThrownBy(scope::snapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void respectsMaxNestingFromProperties() {
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setMaxNestingDepth(1);
        StudioDslModule module = StudioDslModule.create(props);
        NodeBuildContext root = module.newRootContext("wf");
        assertThat(root.maxNestingDepth()).isEqualTo(1);
        assertThat(root.childDepth().nestingDepth()).isEqualTo(1);
    }

    @Test
    void aliasesResolveInAssembledGraph() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "alias-wf",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of("llm", "jiuwen.llm", llmConf()),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));
        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {

            /**
             * invoke.
             *
             * @param messages messages
             * @return result
             * @since 0.1.0
             */

            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("ok");
            }

            /**
             * stream.
             *
             * @param messages messages
             * @return result
             * @since 0.1.0
             */

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        try {
            NodeBuildContext ctx =
                    StudioEngineTestSupport.withCurrentOverrides(module.newRootContext("alias-wf"));
            Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                    module.registry(),
                    wf,
                    ctx,
                    Map.of("userFields", Map.of("query", "hi")),
                    mock(NodeSessionApi.class),
                    mock(ModelContext.class));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf).containsEntry("raw_output", "ok");
            assertThat(ctx.variableScope().isClosed()).isTrue();
        } finally {
            StudioEngineTestSupport.clear();
        }
    }
}
