/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * PythonCodeExecutorTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class PythonCodeExecutorTest {
    private static boolean pythonAvailable;

    @BeforeAll
    static void checkPython() throws Exception {
        try {
            Process p = new ProcessBuilder("python3", "-c", "print(1)").start();
            try (java.io.InputStream out = p.getInputStream(); java.io.InputStream err = p.getErrorStream()) {
                out.transferTo(java.io.OutputStream.nullOutputStream());
                err.transferTo(java.io.OutputStream.nullOutputStream());
                pythonAvailable = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (IOException e) {
            pythonAvailable = false;
        }
    }

    @Test
    void subprocess_runsMainAndReturnsJson() {
        assumeTrue(pythonAvailable, "python3 not available");
        SubprocessPythonCodeExecutor exec = new SubprocessPythonCodeExecutor();
        PythonExecResult result = exec.execute(new PythonExecRequest(
                "n1",
                "def main(args):\n    return {'echo': args.get('x'), 'ok': True}\n",
                Map.of("x", "hi"),
                10_000L,
                "python3"));
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.outputs()).containsEntry("echo", "hi");
        assertThat(result.outputs()).containsEntry("ok", true);
    }

    @Test
    void subprocess_timeout_isDistinguishable() {
        assumeTrue(pythonAvailable, "python3 not available");
        SubprocessPythonCodeExecutor exec = new SubprocessPythonCodeExecutor();
        assertThatThrownBy(() -> exec.execute(new PythonExecRequest(
                        "n1",
                        "import time\ndef main(args):\n    time.sleep(5)\n    return {}\n",
                        Map.of(),
                        200L,
                        "python3")))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.PYTHON_TIMEOUT);
    }

    @Test
    void subprocess_nonZeroExit_isDistinguishable(@TempDir Path tmp) {
        assumeTrue(pythonAvailable, "python3 not available");
        SubprocessPythonCodeExecutor exec = new SubprocessPythonCodeExecutor();
        assertThatThrownBy(() -> exec.execute(request(
                        "nz1",
                        "def main(args):\n    raise RuntimeError('boom')\n",
                        tmp)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.PYTHON_NON_ZERO);
        assertNoLeftoverScripts(tmp);
    }

    @Test
    void subprocess_nonObjectStdout_isPythonIo(@TempDir Path tmp) {
        assumeTrue(pythonAvailable, "python3 not available");
        SubprocessPythonCodeExecutor exec = new SubprocessPythonCodeExecutor();
        // main returns a list → json.dumps produces array → PYTHON_IO (stdout not object)
        assertThatThrownBy(() -> exec.execute(request(
                        "io1",
                        "def main(args):\n    return [1, 2, 3]\n",
                        tmp)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.PYTHON_IO);
        assertNoLeftoverScripts(tmp);
    }

    @Test
    void subprocess_timeout_cleansIsolationWorkDir(@TempDir Path tmp) {
        assumeTrue(pythonAvailable, "python3 not available");
        SubprocessPythonCodeExecutor exec = new SubprocessPythonCodeExecutor();
        assertThatThrownBy(() -> exec.execute(request(
                        "to1",
                        "import time\ndef main(args):\n    time.sleep(5)\n    return {}\n",
                        tmp,
                        200L)))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.PYTHON_TIMEOUT);
        assertNoLeftoverScripts(tmp);
    }

    private static PythonExecRequest request(String nodeId, String script, Path workdirRoot) {
        return request(nodeId, script, workdirRoot, 10_000L);
    }

    private static PythonExecRequest request(String nodeId, String script, Path workdirRoot, long timeoutMs) {
        return new PythonExecRequest(
                nodeId,
                script,
                Map.of(),
                timeoutMs,
                "python3",
                "tenant-t",
                "wf-exec-t",
                workdirRoot.toString(),
                false,
                List.of("PATH", "LANG"));
    }

    private static void assertNoLeftoverScripts(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> leftover = walk.filter(p -> "script.py".equals(p.getFileName().toString())).toList();
            assertThat(leftover).as("isolation workdir must be cleaned (no script.py left)").isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
