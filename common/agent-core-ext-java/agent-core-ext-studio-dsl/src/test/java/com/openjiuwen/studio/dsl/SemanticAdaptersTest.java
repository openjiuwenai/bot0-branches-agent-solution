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
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

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
    private final NodeBuildContext ctx = NodeBuildContext.defaults("wf");
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
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("tier", "gold")), session, model);
        assertThat(out.get("branchId")).isEqualTo("vip");
    }

    @Test
    void loop_iteratesMaxTimes() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("l1", "jiuwen.loop", Map.of("maxIterations", 3)), ctx);
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
                AssembledNode.of("m1", "jiuwen.message", Map.of("message", "hello ${name}")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of("name", "Kayla")), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("result")).isEqualTo("hello Kayla");
    }

    @Test
    void extractor_pullsDeclaredFields() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "e1",
                        "jiuwen.extractor",
                        Map.of("extractFields", List.of(Map.of("name", "city", "path", "addr.city")))),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("addr", Map.of("city", "SZ"))), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("city")).isEqualTo("SZ");
    }

    @Test
    void streamTransform_includeAndMap() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "s1",
                        "jiuwen.streamTransform",
                        Map.of(
                                "includeFields",
                                List.of("a", "b"),
                                "fieldMapping",
                                Map.of("a", "alpha"))),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("a", 1, "b", 2, "c", 3)), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("a", 1).containsEntry("b", 2).containsEntry("alpha", 1).doesNotContainKey("c");
    }

    @Test
    void plugin_mockResponse() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("p1", "jiuwen.plugin", Map.of("mockResponse", Map.of("ok", true))), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("ok", true);
    }

    @Test
    void mcp_withoutInvoker_failsSurface() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("mcp1", "jiuwen.mcp", Map.of("tool", "x")), ctx);
        assertThatThrownBy(() -> exec.invoke(Map.of(), session, model))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void questioner_emitsInputRequired() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("q1", "jiuwen.questioner", Map.of("question", "name?")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("hangState")).isEqualTo("INPUT_REQUIRED");
    }

    @Test
    void intent_keywordMatch() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "i1",
                        "jiuwen.intentDetection",
                        Map.of(
                                "intents",
                                List.of(Map.of("intentId", "refund", "keywords", List.of("退款", "refund"))))),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("query", "我要退款")), session, model);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("intent")).isEqualTo("refund");
    }
}
