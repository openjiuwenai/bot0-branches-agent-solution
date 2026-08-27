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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Process-level Python isolation aligned with Studio LocalCodeRunner wrap contract (L2 §4.3.2).
 * User code must define {@code main(args: dict) -> dict}; stdout must be JSON only.
 *
 * @since 2026-08-17
 */
public final class SubprocessPythonCodeExecutor implements PythonCodeExecutor {
    /**
     * execute.
     *
     * @param request request
     * @return result
     * @throws NodeExecutionException when the call fails
     */
    @Override
    public PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException {
        Path workDir = null;
        try {
            workDir = createIsolationWorkDir(request);
            return runProcess(request, workDir);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        } catch (IOException | IllegalArgumentException e) {
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        } finally {
            cleanup(workDir);
        }
    }

    private static PythonExecResult runProcess(PythonExecRequest request, Path workDir)
            throws IOException, InterruptedException, NodeExecutionException {
        Path scriptFile = workDir.resolve("script.py");
        Files.writeString(scriptFile, buildWrappedCode(request.script(), request.inputs()), StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(request.interpreter(), "-I", scriptFile.toAbsolutePath().toString());
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        applyEnvironment(pb, request);
        Process process = pb.start();
        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            Future<?> tOut = pool.submit(() -> drainStream(process.getInputStream(), stdoutBuf));
            Future<?> tErr = pool.submit(() -> drainStream(process.getErrorStream(), stderrBuf));
            boolean finished = process.waitFor(Math.max(1L, request.timeoutMs()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                awaitDrain(tOut);
                awaitDrain(tErr);
                throw new NodeExecutionException(
                        request.nodeId(),
                        "jiuwen.code",
                        NodeCauseCode.PYTHON_TIMEOUT,
                        "python exceeded timeoutMs=" + request.timeoutMs());
            }
            awaitDrain(tOut);
            awaitDrain(tErr);
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
        return new PythonExecResult(parseJsonObject(stdout), stdout, stderr, code);
    }

    private static void drainStream(InputStream in, ByteArrayOutputStream buf) {
        try {
            in.transferTo(buf);
        } catch (IOException ignored) {
            // process ended
        }
    }

    private static void awaitDrain(Future<?> future) {
        try {
            future.get(2L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
        } catch (InterruptedException e) {
            future.cancel(true);
        } catch (ExecutionException ignored) {
            // drain IO already swallowed
        }
    }

    private static void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Isolation workdir: workdir-root / tenant / workflowExecutionId / nodeId / uuid (L2 §3.8).
     *
     * @param request request
     * @return created directory
     * @throws IOException when the directory cannot be created
     */
    public static Path createIsolationWorkDir(PythonExecRequest request) throws IOException {
        Path root;
        if (request.workdirRoot() != null && !request.workdirRoot().isBlank()) {
            root = Path.of(request.workdirRoot());
        } else {
            root = Path.of(System.getProperty("java.io.tmpdir"), "studio-dsl-python");
        }
        String tenantSeg = sanitize(request.tenantId() == null || request.tenantId().isBlank()
                ? "default"
                : request.tenantId());
        String wfSeg = sanitize(request.workflowExecutionId() == null || request.workflowExecutionId().isBlank()
                ? "wf"
                : request.workflowExecutionId());
        String nodeSeg = sanitize(request.nodeId() == null || request.nodeId().isBlank() ? "node" : request.nodeId());
        Path base = root.resolve(tenantSeg).resolve(wfSeg).resolve(nodeSeg);
        Files.createDirectories(base);
        return Files.createTempDirectory(base, "run-");
    }

    static void applyEnvironment(ProcessBuilder pb, PythonExecRequest request) {
        if (request.inheritEnv()) {
            pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            return;
        }
        pb.environment().clear();
        for (String key : request.envWhitelist()) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String val = System.getenv(key);
            if (val != null) {
                pb.environment().put(key, val);
            }
        }
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
    }

    static String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.isEmpty() ? "x" : (s.length() > 64 ? s.substring(0, 64) : s);
    }

    public static String buildWrappedCode(String userCode, Map<String, Object> inputs) {
        String inputsLiteral = toPythonLiteral(inputs == null ? Map.of() : inputs);
        return ""
                + "import sys, json, io\n"
                + "_console_buffer = io.StringIO()\n"
                + "_original_stdout = sys.stdout\n"
                + "sys.stdout = _console_buffer\n"
                + userCode
                + "\n"
                + "args = "
                + inputsLiteral
                + "\n"
                + "try:\n"
                + "    result = main(args)\n"
                + "finally:\n"
                + "    sys.stdout = _original_stdout\n"
                + "    _console_log = _console_buffer.getvalue()\n"
                + "    if _console_log:\n"
                + "        sys.stderr.write(_console_log)\n"
                + "print(json.dumps(result, default=str))\n";
    }

    /**
     * Embed inputs as a Python literal (Python {@code repr(inputs)} in {@code build_wrapped_code}).
     */
    static String toPythonLiteral(Map<String, Object> inputs) {
        return pythonRepr(inputs);
    }

    static String pythonRepr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(pythonRepr(String.valueOf(e.getKey())))
                        .append(": ")
                        .append(pythonRepr(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(pythonRepr(item));
            }
            sb.append(']');
            return sb.toString();
        }
        return pythonStringRepr(String.valueOf(value));
    }

    private static String pythonStringRepr(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
            sb.append(jsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> cast = new LinkedHashMap<>();
            m.forEach((k, val) -> cast.put(String.valueOf(k), val));
            return toJson(cast);
        }
        return '"' + escapeJson(String.valueOf(v)) + '"';
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(String stdout) throws IOException {
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) {
            return Map.of();
        }
        // Minimal JSON object parser for flat/nested maps via simple approach: use javax? avoid deps —
        // support flat string/number/bool/null and one-level objects produced by json.dumps.
        if (!trimmed.startsWith("{")) {
            throw new IOException("python stdout is not a JSON object: " + truncate(trimmed));
        }
        return (Map<String, Object>) SimpleJson.parse(trimmed);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    /** Tiny JSON subset parser sufficient for python json.dumps(dict) results. */
    private static final class JsonNull {
        private static final JsonNull INSTANCE = new JsonNull();

        private JsonNull() {}
    }

    public static final class SimpleJson {
        private final String s;
        private int i;

        private SimpleJson(String s) {
            this.s = s;
        }

        public static Object parse(String s) throws IOException {
            SimpleJson p = new SimpleJson(s.trim());
            Object v = p.parseValue();
            p.skipWs();
            if (p.i != p.s.length()) {
                throw new IOException("trailing junk in json");
            }
            return v;
        }

        private Object parseValue() throws IOException {
            skipWs();
            if (i >= s.length()) {
                throw new IOException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f' || c == 'n' || c == '-' || Character.isDigit(c)) {
                return parseLiteral();
            }
            throw new IOException("unexpected char " + c);
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object parsed = parseValue();
                if (parsed instanceof JsonNull) {
                    map.put(key, null);
                } else {
                    map.put(key, parsed);
                }
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private java.util.List<Object> parseArray() throws IOException {
            expect('[');
            java.util.List<Object> list = new java.util.ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                Object parsed = parseValue();
                if (parsed instanceof JsonNull) {
                    list.add(null);
                } else {
                    list.add(parsed);
                }
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"', '\\', '/' -> sb.append(n);
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IOException("unterminated string");
        }

        private Object parseLiteral() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.') {
                    i++;
                } else {
                    break;
                }
            }
            String lit = s.substring(start, i);
            if ("true".equals(lit)) {
                return Boolean.TRUE;
            }
            if ("false".equals(lit)) {
                return Boolean.FALSE;
            }
            if ("null".equals(lit)) {
                return JsonNull.INSTANCE;
            }
            if (lit.contains(".")) {
                return Double.valueOf(lit);
            }
            return Long.valueOf(lit);
        }

        private void expect(char c) throws IOException {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IOException("expected " + c);
            }
            i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
