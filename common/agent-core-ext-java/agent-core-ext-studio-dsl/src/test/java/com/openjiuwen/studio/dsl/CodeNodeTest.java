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
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.CodeLogic;
import com.openjiuwen.studio.dsl.spi.CodeLogicContext;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * CodeNodeTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class CodeNodeTest {
    @Test
    void javaSpi_executesAndReturnsUserFields() throws Exception {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        CodeLogicRegistry logics = new CodeLogicRegistry();
        logics.register(new CodeLogic() {
            @Override
            public String name() {
                return "double";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> inputs, CodeLogicContext ctx) {
                Object v = inputs.get("n");
                long n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v));
                return Map.of("n", n * 2);
            }
        });
        NodeBuildContext ctx = new NodeBuildContext("wf", 0, 5, null, logics, c -> null);
        ComponentExecutable exec = registry.create(
                AssembledNode.of("c1", "jiuwen.code", Map.of("codeLogicRef", "double")), ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(
                        Map.of("userFields", Map.of("n", 21)),
                        mock(NodeSessionApi.class),
                        mock(ModelContext.class));
        assertThat(out).containsKey("userFields");
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("n")).isEqualTo(42L);
    }

    @Test
    void missingCodeLogic_failsWithSurface() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = NodeBuildContext.defaults("wf");
        ComponentExecutable exec = registry.create(
                AssembledNode.of("c1", "jiuwen.code", Map.of("codeLogicRef", "nope")), ctx);
        assertThatThrownBy(() -> exec.invoke(Map.of(), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.CODE_LOGIC_NOT_FOUND);
    }

    @Test
    void bothJavaAndPythonWithoutLanguage_isAmbiguous() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = NodeBuildContext.defaults("wf");
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of("codeLogicRef", "double", "code", "def main(args):\n  return {}\n")),
                ctx);
        assertThatThrownBy(() -> exec.invoke(
                Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.CODE_PATH_AMBIGUOUS);
    }
}
