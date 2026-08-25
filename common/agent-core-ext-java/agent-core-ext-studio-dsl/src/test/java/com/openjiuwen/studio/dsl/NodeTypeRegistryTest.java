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
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

/**
 * NodeTypeRegistryTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class NodeTypeRegistryTest {
    @Test
    void builtins_coverTwentyOneCanonicalTypes() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        BuiltinNodeBootstrap.registerAll(registry);
        assertThat(registry.canonicalTypes()).hasSize(21);
        assertThat(registry.canonicalize("jiuwen.llm")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.flowApi")).isEqualTo("jiuwen.plugin");
        assertThat(registry.canonicalize("jiuwen.workflowComposite")).isEqualTo("jiuwen.subWorkflow");
    }

    @Test
    void unknownType_failsWithSurface() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThatThrownBy(() -> registry.canonicalize("EI.qa"))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.UNKNOWN_NODE_TYPE);
    }

    @Test
    void programmaticCustomFactory_canRegisterAndCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        registry.register(new NodeHandlerFactory() {
            @Override
            public String canonicalType() {
                return "demo.custom";
            }

            @Override
            public Set<String> aliases() {
                return Set.of();
            }

            @Override
            public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
                return registry.create(AssembledNode.of(node.id(), "jiuwen.start", Map.of()), ctx);
            }
        });
        ComponentExecutable exec =
                registry.create(AssembledNode.of("n1", "demo.custom", Map.of()), NodeBuildContext.defaults("wf"));
        assertThat(exec).isNotNull();
    }

    @Test
    void customCannotOverrideBuiltinCanonical() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThatThrownBy(() -> registry.register(new NodeHandlerFactory() {
                    @Override
                    public String canonicalType() {
                        return "jiuwen.start";
                    }

                    @Override
                    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
                        return null;
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void allTwentyOne_canCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = NodeBuildContext.defaults("wf");
        // nested needs resolver — use non-nested types here
        for (String type : registry.canonicalTypes()) {
            if ("jiuwen.subWorkflow".equals(type)) {
                continue;
            }
            if ("jiuwen.code".equals(type)) {
                AssembledNode code = AssembledNode.of(
                        "c1", "jiuwen.code", Map.of("codeLogicRef", "missing"));
                // create succeeds; invoke would fail CODE_LOGIC_NOT_FOUND
                assertThat(registry.create(code, ctx)).isNotNull();
                continue;
            }
            assertThat(registry.create(AssembledNode.of("n-" + type, type, Map.of()), ctx))
                    .as(type)
                    .isNotNull();
        }
    }
}
