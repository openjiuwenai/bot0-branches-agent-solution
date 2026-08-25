/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Host-style IT: consume assembled products (no DSL loader) via {@link WorkflowAssemblyBridge}
 * and verify variable scope closes when the workflow run completes (L2 §7.4 StudioDslHostIT).
 *
 * @since 2026-08-17
 */
class StudioDslHostIT {
    @Test
    void host_assemblesAndRunsLinearWorkflow_thenVariableScopeClosed() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "host-wf-1",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "v",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("greeting", "hello"))),
                        AssembledNode.of("m", "jiuwen.message", Map.of("message", "done")),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));

        NodeBuildContext ctx = module.newRootContext("host-wf-1", "tenant-host");
        WorkflowVariableScope scope = ctx.variableScope();
        WorkflowAssemblyBridge bridge = module.assemblyBridge();

        assertThat(bridge.mapExecutables(wf, ctx)).hasSize(4);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = bridge.executeLinear(
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
    void host_respectsMaxNestingFromProperties() {
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setMaxNestingDepth(1);
        StudioDslModule module = StudioDslModule.create(props);
        NodeBuildContext root = module.newRootContext("wf");
        assertThat(root.maxNestingDepth()).isEqualTo(1);
        assertThat(root.childDepth().nestingDepth()).isEqualTo(1);
    }

    @Test
    void host_aliasesResolveInAssembledGraph() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "alias-wf",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of("llm", "jiuwen.llm", Map.of("mockOutput", "ok")),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));
        NodeBuildContext ctx = module.newRootContext("alias-wf");
        Map<String, Object> out = module.assemblyBridge()
                .executeLinear(
                        wf,
                        ctx,
                        Map.of("userFields", Map.of()),
                        mock(NodeSessionApi.class),
                        mock(ModelContext.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf).containsEntry("text", "ok");
        assertThat(ctx.variableScope().isClosed()).isTrue();
    }
}
