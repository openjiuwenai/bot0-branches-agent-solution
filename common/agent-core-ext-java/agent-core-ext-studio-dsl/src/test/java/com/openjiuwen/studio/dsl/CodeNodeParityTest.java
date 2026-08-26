/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.InprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.python.PythonCodeRunners;
import com.openjiuwen.studio.dsl.python.SandboxPythonCodeExecutor;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.util.FlowCodeSchemaSupport;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2 Code parity: runners / schema / blacklist (Python FlowCode).
 *
 * @since 2026-08-25
 */
class CodeNodeParityTest {
    private static boolean pythonAvailable;

    @BeforeAll
    static void checkPython() throws Exception {
        try {
            Process p = new ProcessBuilder("python3", "-c", "print(1)").start();
            try (java.io.InputStream out = p.getInputStream();
                    java.io.InputStream err = p.getErrorStream()) {
                out.transferTo(java.io.OutputStream.nullOutputStream());
                err.transferTo(java.io.OutputStream.nullOutputStream());
                pythonAvailable = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (IOException e) {
            pythonAvailable = false;
        }
    }

    @AfterEach
    void clearSandbox() {
        PythonCodeRunners.setSandboxExecutor(null);
        System.clearProperty("CODE_BLACK_LIST");
        System.clearProperty("studio.dsl.code.local-exec-mode");
    }

    @Test
    void runners_resolve_inprocess_and_subprocess() {
        assertThat(PythonCodeRunners.resolve("local", "inprocess", null))
                .isInstanceOf(InprocessPythonCodeExecutor.class);
        assertThat(PythonCodeRunners.resolve("local", "subprocess", null))
                .isInstanceOf(SubprocessPythonCodeExecutor.class);
        assertThat(PythonCodeRunners.normalizeExecEnv("wasm")).isEqualTo("local");
    }

    @Test
    void runners_sandbox_without_host_falls_back_to_local() {
        PythonCodeRunners.setSandboxExecutor(null);
        assertThat(PythonCodeRunners.resolve("sandbox", "subprocess", null))
                .isInstanceOf(SubprocessPythonCodeExecutor.class);
    }

    @Test
    void runners_sandbox_uses_configured_executor() {
        PythonCodeExecutor marker = new SandboxPythonCodeExecutor();
        PythonCodeRunners.setSandboxExecutor(marker);
        assertThat(PythonCodeRunners.resolve("sandbox", "inprocess", null)).isSameAs(marker);
    }

    @Test
    void coerce_inputs_none_string_to_empty() {
        Map<String, Object> in = new HashMap<>();
        in.put("input", null);
        Map<String, Object> coerced = TypeCoercer.coerceByFieldList(
                in, List.of(Map.of("id", "input", "type", "string")), true);
        assertThat(coerced.get("input")).isEqualTo("");
    }

    @Test
    void coerce_outputs_array_integer() {
        Map<String, Object> coerced = TypeCoercer.coerceByFieldList(
                Map.of("ids", List.of("1", "2")),
                List.of(Map.of(
                        "id",
                        "ids",
                        "type",
                        "array",
                        "schema",
                        Map.of("type", "integer"))),
                false);
        assertThat(coerced.get("ids")).isEqualTo(List.of(1L, 2L));
    }

    @Test
    void blacklist_blocks_keyword() {
        System.setProperty("CODE_BLACK_LIST", "[\"os.system\"]");
        assertThatThrownBy(() -> FlowCodeSchemaSupport.checkBlacklist("n1", "import os\nos.system('x')\n"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("black list");
    }

    @Test
    void inprocess_python_via_handler() {
        assumeTrue(pythonAvailable, "python3 not available");
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setLocalExecMode("inprocess");
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of(
                                "language",
                                "python",
                                "code",
                                "def main(args):\n    return {'echo': args.get('x')}\n",
                                "exec_env",
                                "local",
                                "userFields",
                                Map.of(
                                        "inputs",
                                        List.of(Map.of("id", "x", "type", "string")),
                                        "outputs",
                                        List.of(Map.of("id", "echo", "type", "string"))))),
                ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("x", 42)), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("echo")).isEqualTo("42");
    }

    @Test
    void sandbox_failure_falls_back_to_local() {
        assumeTrue(pythonAvailable, "python3 not available");
        AtomicInteger sandboxCalls = new AtomicInteger();
        PythonCodeRunners.setSandboxExecutor(req -> {
            sandboxCalls.incrementAndGet();
            throw new NodeExecutionException(
                    req.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_NON_ZERO, "sandbox boom");
        });
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setLocalExecMode("subprocess");
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of(
                                "language",
                                "python",
                                "code",
                                "def main(args):\n    return {'ok': True}\n",
                                "exec_env",
                                "sandbox")),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of()), null, null);
        assertThat(sandboxCalls.get()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("ok")).isEqualTo(true);
    }

    @Test
    void stream_yieldsSingleInvokeResult() {
        assumeTrue(pythonAvailable, "python3 not available");
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setLocalExecMode("inprocess");
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of(
                                "language",
                                "python",
                                "code",
                                "def main(args):\n    return {'v': 1}\n")),
                ctx);
        java.util.Iterator<Object> it = exec.stream(Map.of("userFields", Map.of()), null, null);
        java.util.List<Object> frames = new java.util.ArrayList<>();
        it.forEachRemaining(frames::add);
        assertThat(frames).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) frames.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("v")).isEqualTo(1L);
    }

    @Test
    void invoke_tracesCodeInfo() {
        assumeTrue(pythonAvailable, "python3 not available");
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setLocalExecMode("inprocess");
        NodeBuildContext ctx = NodeBuildContext.defaults("wf", props);
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of(
                                "language",
                                "python",
                                "code",
                                "def main(args):\n    print('hi')\n    return {'ok': True}\n")),
                ctx);
        com.openjiuwen.core.session.NodeSessionApi session =
                org.mockito.Mockito.mock(com.openjiuwen.core.session.NodeSessionApi.class);
        exec.invoke(Map.of("userFields", Map.of()), session, null);
        org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce())
                .trace(org.mockito.ArgumentMatchers.argThat(m -> m != null && m.containsKey("code_info")));
    }
}
