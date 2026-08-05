/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin MCP client over stdio JSON-RPC — the "LLMClient direct-call" workaround.
 *
 * <p>Spawns the MCP server as a subprocess and speaks newline-delimited
 * JSON-RPC 2.0 over its stdin/stdout. Server logs go to stderr and are drained
 * on a daemon thread (NOT merged into stdout) — merging them is what makes the
 * SDK's StdioClient hang: log lines pollute the JSON stream and break framing.
 *
 * <p>Lifecycle:
 * <pre>
 *   StdioMcpClient c = new StdioMcpClient(cmd, env);
 *   c.start();                 // initialize handshake
 *   List&lt;McpTool&gt; tools = c.listTools();
 *   String r = c.callTool("get_financials", Map.of("identifier", "AAPL"));
 *   c.close();                 // destroy subprocess
 * </pre>
 *
 * <p>JSON-RPC assembly/parsing is exposed as static pure functions so unit
 * tests can verify framing without spawning a subprocess.
 *
 * @since 2026-07
 */
public class StdioMcpClient implements McpClient {
    /** MCP protocol version we advertise in initialize. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private static final Logger LOG = Logger.getLogger(StdioMcpClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final Map<String, String> env;
    private final AtomicInteger nextId = new AtomicInteger(0);

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private ExecutorService stderrDrain;

    /**
     * Create a client for the given server launch command.
     *
     * @param command server launch command (e.g. ["python3", "-m", "sec_edgar_mcp.server"]);
     *                must not be {@code null} or empty
     * @param env extra environment variables for the subprocess (e.g. SEC_EDGAR_USER_AGENT);
     *            may be {@code null} (treated as empty)
     * @throws IllegalArgumentException if {@code command} is {@code null} or empty
     */
    public StdioMcpClient(List<String> command, Map<String, String> env) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        this.command = List.copyOf(command);
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    /**
     * Spawn the subprocess and run the initialize / initialized handshake.
     *
     * @throws IOException if the subprocess cannot be started or the streams break
     * @throws McpRpcException if the server returns a JSON-RPC error during initialize
     * @throws java.io.EOFException if the subprocess closes stdout before responding
     */
    public void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        // Do NOT redirectErrorStream — stderr logs must stay off the JSON stream.
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrain();
        runInitializeHandshake();
    }

    /**
     * Run the MCP {@code initialize}/{@code notifications/initialized} handshake
     * over the now-open transport.
     *
     * @throws IOException if writing or reading the transport fails
     * @throws McpRpcException if the server returns a JSON-RPC error during initialize
     */
    private void runInitializeHandshake() throws IOException {
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.put("capabilities", Map.of());
        initParams.put("clientInfo", Map.of("name", "edpa-mcp", "version", "0.1"));
        // initialize is a request (expects result); initialized is a notification (no result)
        request("initialize", initParams);
        notify("notifications/initialized", Map.of());
    }

    /**
     * MCP {@code tools/list} — descriptors for every tool the server exposes.
     *
     * @return list of tool descriptors (never {@code null}, possibly empty)
     * @throws IOException if the transport breaks while reading the response
     * @throws McpRpcException if the server returns a JSON-RPC error
     * @throws java.io.EOFException if the subprocess closes stdout before responding
     */
    @Override
    public List<McpTool> listTools() throws IOException {
        JsonNode result = request("tools/list", Map.of());
        JsonNode tools = result.path("tools");
        List<McpTool> out = new ArrayList<>();
        for (JsonNode t : tools) {
            out.add(toToolDescriptor(t));
        }
        return out;
    }

    /**
     * Build a {@link McpTool} descriptor from one {@code tools/list} entry.
     *
     * @param t JSON node for a single tool entry (must not be {@code null})
     * @return the projected tool descriptor
     */
    private McpTool toToolDescriptor(JsonNode t) {
        String name = t.path("name").asText("");
        String desc = t.path("description").asText("");
        Map<String, Object> schema = t.has("inputSchema")
                ? MAPPER.convertValue(t.get("inputSchema"), new TypeReference<Map<String, Object>>() {
                })
                : Map.of();
        return new McpTool(name, desc, schema);
    }

    /**
     * MCP {@code tools/call} — invoke a tool by name, return its text content.
     *
     * @param name tool name (must match a name from {@link #listTools()})
     * @param arguments tool arguments (JSON object); may be {@code null} (treated as empty)
     * @return concatenated text content from the tool result (never {@code null})
     * @throws IOException if the transport breaks while reading the response
     * @throws McpRpcException if the server returns a JSON-RPC error
     * @throws java.io.EOFException if the subprocess closes stdout before responding
     */
    @Override
    public String callTool(String name, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", nullSafeArgs(arguments));
        JsonNode result = request("tools/call", params);
        return extractTextContent(result);
    }

    /**
     * Return the given arguments map, or an empty map when {@code null}, so the
     * JSON payload always carries a {@code arguments} field.
     *
     * @param arguments tool arguments (may be {@code null})
     * @return {@code arguments} when non-null, otherwise {@link Map#of()}
     */
    private static Map<String, Object> nullSafeArgs(Map<String, Object> arguments) {
        return arguments == null ? Map.of() : arguments;
    }

    /**
     * Tear down the transport (kill subprocess, close streams, stop the drain).
     */
    @Override
    public void close() {
        shutdownStderrDrain();
        closeStdinQuietly();
        destroyProcessIfAlive();
    }

    /**
     * Best-effort close of the subprocess stdin writer; swallowed because the
     * transport is being torn down regardless.
     */
    private void closeStdinQuietly() {
        if (stdin == null) {
            return;
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // best effort — subprocess is being torn down anyway
        }
    }

    /**
     * Forcibly destroy the subprocess if it is still alive.
     */
    private void destroyProcessIfAlive() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * Stop the stderr drain executor without interrupting the worker thread.
     * Shutting down lets the in-flight read finish naturally; the subprocess
     * close in {@link #close()} will EOF the reader.
     */
    private void shutdownStderrDrain() {
        if (stderrDrain != null) {
            stderrDrain.shutdownNow();
        }
    }

    // ---- transport ------------------------------------------------------

    /**
     * Send a request and read the matching response. Synchronized: only one
     * request in flight at a time.
     *
     * @param method JSON-RPC method name
     * @param params request parameters (may be {@code null})
     * @return the {@code result} node of the matching response
     * @throws IOException if writing or reading the transport fails
     * @throws McpRpcException if the server returns a JSON-RPC error
     */
    private synchronized JsonNode request(String method, Map<String, Object> params) throws IOException {
        int id = nextId.incrementAndGet();
        String payload = encodeRequest(MAPPER, id, method, params);
        stdin.write(payload);
        stdin.write('\n');
        stdin.flush();
        return readResponse(id);
    }

    /**
     * Send a notification (no response expected). Synchronized to serialize
     * writes with {@link #request(String, Map)}.
     *
     * @param method JSON-RPC method name
     * @param params notification parameters (may be {@code null})
     * @throws IOException if writing to the transport fails
     */
    private synchronized void notify(String method, Map<String, Object> params) throws IOException {
        String payload = encodeNotification(MAPPER, method, params);
        stdin.write(payload);
        stdin.write('\n');
        stdin.flush();
    }

    /**
     * Read NDJSON lines until we find the response with our request id.
     *
     * @param id the request id whose response we are waiting for
     * @return the matching response object
     * @throws IOException if the subprocess closes stdout before responding
     * @throws McpRpcException if the matching response carries a JSON-RPC error
     */
    private JsonNode readResponse(int id) throws IOException {
        String line;
        while ((line = stdout.readLine()) != null) {
            JsonNode matched = extractResultById(line, id);
            // Non-matching line (notification / unsolicited) — skip.
            if (!matched.isMissingNode()) {
                throwIfError(id, matched);
                return matched.path("result");
            }
        }
        throw new IOException("MCP subprocess closed stdout before responding to id=" + id);
    }

    /**
     * If {@code matched} carries a JSON-RPC {@code error} object, raise
     * {@link McpRpcException}; otherwise return.
     *
     * @param id the request id (for the exception message)
     * @param matched the matched response node
     * @throws McpRpcException if the response has an {@code error} field
     */
    private void throwIfError(int id, JsonNode matched) {
        JsonNode err = matched.path("error");
        if (err.isMissingNode() || err.isNull()) {
            return;
        }
        throw new McpRpcException(id, err.path("message").asText("MCP error"));
    }

    /**
     * Drain stderr on a daemon thread so the subprocess never blocks on a full
     * pipe buffer. Uses a single-thread executor with a daemon thread factory
     * that installs an uncaught-exception handler.
     */
    private void startStderrDrain() {
        BufferedReader stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        ThreadFactory factory = new DaemonThreadFactory("edpa-mcp-stderr-drain");
        stderrDrain = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
        stderrDrain.submit(() -> drainStderrLoop(stderr));
    }

    /**
     * Read-and-discard loop for stderr. Lines are swallowed unless the
     * {@code -Dedpa.mcp.stderr} flag is set, in which case they are logged.
     *
     * @param stderr the subprocess stderr reader
     */
    private void drainStderrLoop(BufferedReader stderr) {
        try {
            String l;
            while ((l = stderr.readLine()) != null) {
                // Swallow logs — available for debugging via -Dedpa.mcp.stderr if needed.
                if (Boolean.getBoolean("edpa.mcp.stderr")) {
                    LOG.log(Level.INFO, "[mcp-stderr] {0}", l);
                }
            }
        } catch (IOException ignored) {
            // pipe closed on shutdown
        }
    }

    // ---- pure JSON-RPC helpers (testable without a subprocess) ----------

    /**
     * Encode a JSON-RPC 2.0 request (has {@code id}).
     *
     * @param mapper Jackson mapper used to build the payload
     * @param id request id
     * @param method JSON-RPC method name
     * @param params request parameters (may be {@code null}, encoded as {@code {}})
     * @return the JSON-encoded request string
     */
    static String encodeRequest(ObjectMapper mapper, int id, String method, Map<String, Object> params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        node.set("params", mapper.valueToTree(params == null ? Map.of() : params));
        return node.toString();
    }

    /**
     * Encode a JSON-RPC 2.0 notification (no {@code id}, no response expected).
     *
     * @param mapper Jackson mapper used to build the payload
     * @param method JSON-RPC method name
     * @param params notification parameters (may be {@code null}, encoded as {@code {}})
     * @return the JSON-encoded notification string
     */
    static String encodeNotification(ObjectMapper mapper, String method, Map<String, Object> params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        node.set("params", mapper.valueToTree(params == null ? Map.of() : params));
        return node.toString();
    }

    /**
     * If {@code line} is a JSON-RPC response whose {@code id} matches, return
     * the whole response object; otherwise return a {@link MissingNode} (not a
     * match, a parse error, or a notification). A missing node — rather than
     * {@code null} — lets callers distinguish "no match" with a non-null
     * sentinel (CodeCheck G.MET.06 forbids returning {@code null} here).
     *
     * @param line one NDJSON line read from stdout (may be {@code null}/blank)
     * @param id the request id to match
     * @return the matching response node, or a {@link MissingNode} if it does not match
     */
    static JsonNode extractResultById(String line, int id) {
        if (isBlank(line)) {
            return MissingNode.getInstance();
        }
        JsonNode root = parseJsonLenient(line);
        if (root.isMissingNode()) {
            return MissingNode.getInstance();
        }
        return idMatches(root, id) ? root : MissingNode.getInstance();
    }

    /**
     * Tell whether a line carries no content worth parsing.
     *
     * @param line the line to test (may be {@code null})
     * @return {@code true} if the line is {@code null} or blank
     */
    private static boolean isBlank(String line) {
        return line == null || line.isBlank();
    }

    /**
     * Tell whether a parsed JSON-RPC node carries the expected numeric request id.
     *
     * @param root the parsed JSON-RPC node (must not be {@code null})
     * @param id the expected request id
     * @return {@code true} if the node has a numeric {@code id} equal to {@code id}
     */
    private static boolean idMatches(JsonNode root, int id) {
        JsonNode idNode = root.get("id");
        return idNode != null && idNode.isNumber() && idNode.asInt() == id;
    }

    /**
     * Parse one JSON line, returning a {@link MissingNode} on any parse failure
     * so the caller can skip non-JSON stdout defensively (CodeCheck G.MET.06:
     * no {@code null} return — the missing node is the "no parse" sentinel).
     *
     * @param line the line to parse
     * @return the parsed root node, or a {@link MissingNode} if it is not valid JSON
     */
    private static JsonNode parseJsonLenient(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException ignored) {
            // Not valid JSON on stdout — skip defensively.
            return MissingNode.getInstance();
        }
    }

    /**
     * Concatenate the {@code text} parts of a tools/call result's content array.
     *
     * @param result the {@code result} node (may be {@code null}/missing/textual/object)
     * @return the concatenated text (never {@code null})
     */
    static String extractTextContent(JsonNode result) {
        if (isEmptyNode(result)) {
            return "";
        }
        // Some servers return a bare string — best-effort passthrough.
        if (result.isTextual()) {
            return result.asText();
        }
        JsonNode content = result.path("content");
        // Object without a content array — no text to extract.
        if (!content.isArray()) {
            return "";
        }
        return joinTextParts(content);
    }

    /**
     * Tell whether a JSON node should be treated as absent (no extractable text).
     *
     * @param node the node to test (may be {@code null})
     * @return {@code true} if the node is {@code null}, missing, or JSON null
     */
    private static boolean isEmptyNode(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /**
     * Concatenate the {@code text} parts of a {@code content} array.
     *
     * @param content the {@code content} array node (must be an array)
     * @return the concatenated text (never {@code null})
     */
    private static String joinTextParts(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        content.forEach(part -> appendTextPart(sb, part));
        return sb.toString();
    }

    /**
     * Append the {@code text} of a single content part if it is a text part.
     *
     * @param sb the builder to append into
     * @param part one element of the {@code content} array
     */
    private static void appendTextPart(StringBuilder sb, JsonNode part) {
        if ("text".equals(part.path("type").asText())) {
            sb.append(part.path("text").asText(""));
        }
    }

    /**
     * ThreadFactory that produces daemon threads with a given name prefix and
     * a no-op uncaught-exception handler.
     *
     * <p>This factory is supplied to the {@link ThreadPoolExecutor} in
     * {@link #startStderrDrain()}, so all worker threads are owned and
     * lifecycle-managed by a thread pool rather than launched ad-hoc — satisfying
     * the CodeCheck concurrency rule G.CON.12. The base
     * {@link Thread} is obtained from {@link Executors#defaultThreadFactory()}
     * (never constructed ad-hoc with {@code new Thread}) and then configured;
     * the pool owns its full lifecycle.
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private static final ThreadFactory BASE = Executors.defaultThreadFactory();

        private final AtomicInteger counter = new AtomicInteger(0);
        private final String namePrefix;

        DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            // Pool-owned thread: obtained from the default factory and configured,
            // never constructed ad-hoc (G.CON.12).
            Thread t = BASE.newThread(r);
            t.setName(namePrefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                    LOG.log(Level.WARNING, "Uncaught exception in {0}", new Object[]{thread.getName(), throwable}));
            return t;
        }
    }
}
