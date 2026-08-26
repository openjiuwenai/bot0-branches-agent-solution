/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.BuiltinNodeBootstrap;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * NodeTypeRegistryTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class NodeTypeRegistryTest {
    @Test
    void builtins_coverTwentyOnePlusEiCanonicalTypes() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        BuiltinNodeBootstrap.registerAll(registry);
        assertThat(registry.canonicalTypes()).hasSize(24);
        assertThat(registry.canonicalize("jiuwen.llm")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.flowApi")).isEqualTo("jiuwen.plugin");
        assertThat(registry.canonicalize("jiuwen.workflowComposite")).isEqualTo("jiuwen.subWorkflow");
        assertThat(registry.canonicalize("EI.qa")).isEqualTo("EI.qa");
        assertThat(registry.canonicalize("EI.ParamOutput")).isEqualTo("EI.ParamOutput");
        assertThat(registry.canonicalize("EI.ComplexIntentDetection"))
                .isEqualTo("EI.ComplexIntentDetection");
    }

    @Test
    void unknownType_failsWithSurface() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThatThrownBy(() -> registry.canonicalize("EI.notAThing"))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.UNKNOWN_NODE_TYPE);
    }

    @Test
    void duplicateBuiltinRegistration_fails() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        BuiltinNodeBootstrap.registerAll(registry);
        assertThatThrownBy(() -> BuiltinNodeBootstrap.registerAll(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void allBuiltinTypes_canCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = NodeBuildContext.defaults("wf");
        // nested needs resolver — use non-nested types here
        for (String type : registry.canonicalTypes()) {
            if ("jiuwen.subWorkflow".equals(type) || "EI.ComplexIntentDetection".equals(type)) {
                continue;
            }
            if ("jiuwen.code".equals(type)) {
                AssembledNode code = AssembledNode.of(
                        "c1", "jiuwen.code", Map.of("code", "def main(args):\n    return {}\n"));
                assertThat(registry.create(code, ctx)).isNotNull();
                continue;
            }
            Map<String, Object> configs = Map.of();
            if ("jiuwen.message".equals(type) || "jiuwen.card".equals(type) || "jiuwen.flowCard".equals(type)) {
                configs = Map.of("template", "ok");
            } else if ("jiuwen.aggregate".equals(type)) {
                configs = Map.of("groups", Map.of("o", List.of("a")));
            }
            assertThat(registry.create(AssembledNode.of("n-" + type, type, configs), ctx))
                    .as(type)
                    .isNotNull();
        }
    }
}
