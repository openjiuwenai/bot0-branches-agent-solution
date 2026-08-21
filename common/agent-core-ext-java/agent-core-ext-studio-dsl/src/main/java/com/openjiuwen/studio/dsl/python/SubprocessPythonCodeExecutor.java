package com.openjiuwen.studio.dsl.python;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.spi.PythonCodeExecutor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Process-level Python isolation aligned with Studio LocalCodeRunner wrap contract (L2 §4.3.2).
 * User code must define {@code main(args: dict) -> dict}; stdout must be JSON only.
 */
public final class SubprocessPythonCodeExecutor implements PythonCodeExecutor {

    @Override
    public PythonExecResult execute(PythonExecRequest request) throws NodeExecutionException {
        Path workDir = null;
        try {
            workDir = createIsolationWorkDir(request);
            Path scriptFile = workDir.resolve("script.py");
            String wrapped = buildWrappedCode(request.script(), request.inputs());
            Files.writeString(scriptFile, wrapped, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(request.interpreter(), "-I", scriptFile.toAbsolutePath().toString());
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            applyEnvironment(pb, request);
            Process process = pb.start();

            boolean finished = process.waitFor(Math.max(1L, request.timeoutMs()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new NodeExecutionException(
                        request.nodeId(),
                        "jiuwen.code",
                        NodeCauseCode.PYTHON_TIMEOUT,
                        "python exceeded timeoutMs=" + request.timeoutMs());
            }

            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            int code = process.exitValue();
            if (code != 0) {
                throw new NodeExecutionException(
                        request.nodeId(),
                        "jiuwen.code",
                        NodeCauseCode.PYTHON_NON_ZERO,
                        "exitCode=" + code + ", stderr=" + truncate(stderr));
            }
            Map<String, Object> outputs = parseJsonObject(stdout);
            return new PythonExecResult(outputs, stdout, stderr, code);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    request.nodeId(), "jiuwen.code", NodeCauseCode.PYTHON_IO, e.getMessage(), e);
        } finally {
            if (workDir != null) {
                try {
                    Files.walk(workDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
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
        }
    }

    /** workdir-root / {tenant|default} / {workflowExecutionId} / {nodeId} / {uuid} — L2 §3.8 */
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

    static String buildWrappedCode(String userCode, Map<String, Object> inputs) {
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

    private static String toPythonLiteral(Map<String, Object> inputs) {
        // Prefer JSON then python-compatible via json.loads in wrapper — embed as JSON string.
        String json = toJson(inputs);
        return "json.loads(" + quotePython(json) + ")";
    }

    private static String quotePython(String s) {
        return "'''" + s.replace("'''", "\\'\\'\\'") + "'''";
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
    private static Map<String, Object> parseJsonObject(String stdout) throws IOException {
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

    private static String readFully(java.io.InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    /** Tiny JSON subset parser sufficient for python json.dumps(dict) results. */
    static final class SimpleJson {
        private final String s;
        private int i;

        private SimpleJson(String s) {
            this.s = s;
        }

        static Object parse(String s) throws IOException {
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
                Object val = parseValue();
                map.put(key, val);
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
                list.add(parseValue());
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
                return null;
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
