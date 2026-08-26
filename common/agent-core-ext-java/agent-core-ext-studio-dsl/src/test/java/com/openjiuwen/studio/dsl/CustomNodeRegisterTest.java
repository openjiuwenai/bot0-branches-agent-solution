/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.support.AcmeEnrichNodeFactory;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Explicit register of custom node types (no ServiceLoader).
 *
 * @since 2026-08-17
 */
class CustomNodeRegisterTest {
    @Test
    void programmaticRegister_createsCustomFactory() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        registry.register(new AcmeEnrichNodeFactory());
        assertThat(registry.canonicalTypes()).contains("acme.enrich");
        ComponentExecutable exec =
                registry.create(AssembledNode.of("n1", "acme.enrich", Map.of("k", 1)), NodeBuildContext.defaults("wf"));
        assertThat(exec).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(
                        Map.of("userFields", Map.of("x", "y")),
                        mock(NodeSessionApi.class),
                        mock(ModelContext.class));
        assertThat(out.get("userFields")).isEqualTo(Map.of("x", "y"));
    }

    @Test
    void llmAliases_normalize() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThat(registry.canonicalize("jiuwen.llm")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.llmChain")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.llm_chain")).isEqualTo("jiuwen.LLMComponent");
    }
}
