package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FEAT-031 / L2 gap regressions: no fake success, variable scope, Python isolation keys. */
class Feat031ComplianceTest {

    @Test
    void llm_withoutWiringOrMock_failsNotFakeSuccess() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("llm1", "jiuwen.LLMComponent", Map.of("prompt", "hi")),
                NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> ((NodeExecutionException) e).causeCode())
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void knowledge_withoutWiringOrMock_failsNotEmptyDocs() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("k1", "jiuwen.knowledgeRetrieval", Map.of()),
                NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> ((NodeExecutionException) e).causeCode())
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void code_rejectsNonJavaPythonLanguage() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("c1", "jiuwen.code", Map.of("language", "javascript", "code", "1")),
                NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> ((NodeExecutionException) e).causeCode())
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }

    @Test
    void variableScope_visibleInWorkflow_closedAfterEnd() {
        NodeBuildContext ctx = NodeBuildContext.defaults("wf-vars");
        WorkflowVariableScope scope = ctx.variableScope();
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("v1", "jiuwen.setVariable", Map.of("variableMapping", Map.of("k", "v"))),
                ctx);
        exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class));
        assertThat(scope.snapshot()).containsEntry("k", "v");

        WorkflowVariableScope child = ctx.childDepth().variableScope();
        assertThat(child.snapshot()).isEmpty();
        assertThat(scope.snapshot()).containsEntry("k", "v");

        scope.close();
        assertThatThrownBy(scope::snapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pythonIsolationDir_partitionsByTenantWorkflowNode(@TempDir Path tmp) throws Exception {
        Path dir = SubprocessPythonCodeExecutor.createIsolationWorkDir(new PythonExecRequest(
                "node-a",
                "def main(args):\n  return {}\n",
                Map.of(),
                1000L,
                "python3",
                "tenant-1",
                "wf-exec-9",
                tmp.toString(),
                false,
                java.util.List.of("PATH", "LANG")));
        assertThat(dir.toString()).contains("tenant-1");
        assertThat(dir.toString()).contains("wf-exec-9");
        assertThat(dir.toString()).contains("node-a");
        assertThat(Files.exists(dir)).isTrue();

        Path otherTenant = SubprocessPythonCodeExecutor.createIsolationWorkDir(new PythonExecRequest(
                "node-a",
                "def main(args):\n  return {}\n",
                Map.of(),
                1000L,
                "python3",
                "tenant-2",
                "wf-exec-9",
                tmp.toString(),
                false,
                java.util.List.of("PATH")));
        assertThat(otherTenant.getParent().getParent().getParent().getFileName().toString())
                .isEqualTo("tenant-2");
        assertThat(dir.getParent().getParent().getParent().getFileName().toString()).isEqualTo("tenant-1");
    }

    @Test
    void javaSpiDisabled_failsExplicitly() {
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setJavaSpiEnabled(false);
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("c1", "jiuwen.code", Map.of("language", "java", "codeLogicRef", "x")),
                ctx);
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(e -> assertThat(((NodeExecutionException) e).getMessage()).contains("java SPI disabled"));
    }
}
