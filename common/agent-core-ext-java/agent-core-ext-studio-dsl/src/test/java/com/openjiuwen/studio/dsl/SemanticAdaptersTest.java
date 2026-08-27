/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.extractor.ExtractorEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.StubModelContext;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * SemanticAdaptersTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */

class SemanticAdaptersTest {
    private final NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
    private NodeBuildContext ctx() {
        return StudioEngineTestSupport.context("wf");
    }
    private final NodeSessionApi session = mock(NodeSessionApi.class);
    private final ModelContext model = mock(ModelContext.class);

    @Test
    void branch_selectsMatchingBranchId() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "b1",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "vip",
                                                "condition",
                                                Map.of(
                                                        "operator",
                                                        "eq",
                                                        "left",
                                                        Map.of("value", "tier"),
                                                        "right",
                                                        Map.of("value", "gold"))),
                                        Map.of("branchId", "default", "isDefault", true)))),
                ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("tier", "gold")), session, model);
        assertThat(out.get("branchId")).isEqualTo("vip");
    }

    @Test
    void loop_iteratesMaxTimes() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "l1",
                        "jiuwen.loop",
                        Map.of(
                                "maxIterations",
                                3,
                                "loopBody",
                                List.of(Map.of(
                                        "id",
                                        "sv",
                                        "type",
                                        "jiuwen.setVariable",
                                        "configs",
                                        Map.of("variableMapping", Map.of("tick", 1)))))),
                ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of("x", 1)), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("loopCount")).isEqualTo(3);
    }

    @Test
    void message_rendersTemplate() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("m1", "jiuwen.message", Map.of("template", "hello {{name}}")), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of("name", "Kayla")), session, model);
        assertThat(out.get("result")).isEqualTo("hello Kayla");
    }

    @Test
    void extractor_llmExtractsFromContextHistory() {
        StudioEngineTestSupport.installExtractor(msgs -> "{\"city\": \"SZ\"}");
        try {
            ModelContext extractorModel = new StubModelContext(new com.openjiuwen.core.foundation.llm.schema.UserMessage("住在深圳"));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "e1",
                            "jiuwen.extractor",
                            Map.of(
                                    "model",
                                    Map.of(
                                            "modelName", "mock",
                                            "modelType", "Mock",
                                            "hyperParameters", Map.of("temperature", 0.1),
                                            "extension",
                                            Map.of("api_key", "k", "api_base", "http://localhost/v1")),
                                    "fieldNames",
                                    List.of(
                                            Map.of(
                                                    "field_name", "city",
                                                    "cn_field_name", "城市",
                                                    "description", "城市")))),
                    ctx());
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) exec.invoke(
                    Map.of("userFields", Map.of()), session, extractorModel);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf.get("city")).isEqualTo("SZ");
            assertThat(uf.get("USER_RESPONSE")).isEqualTo("住在深圳");
        } finally {
            StudioEngineTestSupport.clear();
        }
    }

    @Test
    void streamTransform_frameTemplateAndVariables() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "s1",
                        "jiuwen.streamTransform",
                        Map.of(
                                "transformer",
                                Map.of(
                                        "frame_template",
                                        Map.of("result", "{{value}}"),
                                        "variables",
                                        List.of(Map.of("name", "value", "src_path", "a"))))),
                ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("_input", List.of(Map.of("a", 1)))), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("result", 1).containsEntry("is_last", true);
    }

    @Test
    void plugin_mockResponse() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("p1", "jiuwen.plugin", Map.of("mockResponse", Map.of("ok", true))), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("ok", true);
    }

    @Test
    void mcp_invalidConfig_failsSurface() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("mcp1", "jiuwen.mcp", Map.of("tool", "x")), ctx());
        assertThatThrownBy(() -> exec.invoke(Map.of(), session, model))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void questioner_emitsInputRequired() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("q1", "jiuwen.questioner", Map.of("question", "name?")), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("hangState")).isEqualTo("INPUT_REQUIRED");
    }

    @Test
    void intent_stubLlmClassifies() {
        StudioEngineTestSupport.installIntent(messages -> "{\"class\": \"分类0\", \"reason\": \"退款\"}");
        try {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "i1",
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
                                    List.of(Map.of("id", "branch_0", "catalog", "退款")))),
                    ctx());
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    exec.invoke(Map.of("input", "我要退款"), session, model);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf.get("result")).isEqualTo("分类0");
        } finally {
            StudioEngineTestSupport.clear();
        }
    }
}
