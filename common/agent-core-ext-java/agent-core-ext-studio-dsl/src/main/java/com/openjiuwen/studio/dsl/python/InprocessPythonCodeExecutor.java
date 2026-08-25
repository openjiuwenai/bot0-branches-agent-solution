/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.python;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java-side analogue of Python {@code InprocessCodeRunner} selection path.
 *
 * <p>The JVM cannot {@code exec()} CPython; this runner still launches {@code python3} but skips
 * tenant/workflow isolation workdirs (temp file under {@code java.io.tmpdir}), matching the
 * "no SysOperation / lighter local path" intent of inprocess.
 *
 * @since 2026-08-25
 */
public final class InprocessPythonCodeExecutor implements PythonCodeExecutor {
    @Override
    public PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException {
        Path scriptFile = null;
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "studio-dsl-python-inprocess");
            Files.createDirectories(tmp);
            scriptFile = Files.createTempFile(tmp, "inproc-", ".py");
            Files.writeString(
                    scriptFile,
                    SubprocessPythonCodeExecutor.buildWrappedCode(request.script(), request.inputs()),
                    StandardCharsets.UTF_8);
            return runProcess(request, scriptFile);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        } catch (IOException | IllegalArgumentException e) {
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        } finally {
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    private static PythonExecResult runProcess(PythonExecRequest request, Path scriptFile)
            throws IOException, InterruptedException, NodeExecutionException {
        ProcessBuilder pb =
                new ProcessBuilder(request.interpreter(), "-I", scriptFile.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        SubprocessPythonCodeExecutor.applyEnvironment(pb, request);
        Process process = pb.start();
        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            Future<?> tOut = pool.submit(() -> drain(process.getInputStream(), stdoutBuf));
            Future<?> tErr = pool.submit(() -> drain(process.getErrorStream(), stderrBuf));
            boolean finished = process.waitFor(Math.max(1L, request.timeoutMs()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                await(tOut);
                await(tErr);
                throw new NodeExecutionException(
                        request.nodeId(),
                        "jiuwen.code",
                        NodeCauseCode.PYTHON_TIMEOUT,
                        "python exceeded timeoutMs=" + request.timeoutMs());
            }
            await(tOut);
            await(tErr);
        } finally {
            pool.shutdownNow();
        }
        String stdout = stdoutBuf.toString(StandardCharsets.UTF_8);
        String stderr = stderrBuf.toString(StandardCharsets.UTF_8);
        int code = process.exitValue();
        if (code != 0) {
            throw new NodeExecutionException(
                    request.nodeId(),
                    "jiuwen.code",
                    NodeCauseCode.PYTHON_NON_ZERO,
                    "exitCode=" + code + ", stderr=" + truncate(stderr));
        }
        try {
            return new PythonExecResult(
                    SubprocessPythonCodeExecutor.parseJsonObject(stdout), stdout, stderr, code);
        } catch (IOException e) {
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream buf) {
        try {
            in.transferTo(buf);
        } catch (IOException ignored) {
            // ended
        }
    }

    private static void await(Future<?> future) {
        try {
            future.get(2L, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
        } catch (ExecutionException ignored) {
            // drain
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
