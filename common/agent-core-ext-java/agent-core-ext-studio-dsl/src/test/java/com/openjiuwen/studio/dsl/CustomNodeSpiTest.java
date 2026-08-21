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
import com.openjiuwen.studio.dsl.schema.DslNodeShellValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomNodeSpiTest {

    @Test
    void serviceLoader_registersCustomFactory() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThat(registry.canonicalTypes()).contains("acme.enrich");
        ComponentExecutable exec =
                registry.create(AssembledNode.of("n1", "acme.enrich", Map.of("k", 1)), NodeBuildContext.defaults("wf"));
        assertThat(exec).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("x", "y")), mock(NodeSessionApi.class), mock(ModelContext.class));
        assertThat(out.get("userFields")).isEqualTo(Map.of("x", "y"));
    }

    @Test
    void shellValidator_rejectsMissingConfigs() {
        assertThatThrownBy(() -> DslNodeShellValidator.validateRaw(Map.of("id", "n1", "type", "acme.enrich")))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> ((NodeExecutionException) e).causeCode())
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void llmAliases_normalize() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        assertThat(registry.canonicalize("jiuwen.llm")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.llmChain")).isEqualTo("jiuwen.LLMComponent");
        assertThat(registry.canonicalize("jiuwen.llm_chain")).isEqualTo("jiuwen.LLMComponent");
    }
}
