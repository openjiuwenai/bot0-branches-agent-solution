/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.rail;

import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.channel.ToolDataKey;
import com.huawei.ascend.edp.channel.ToolDataKeyFactory;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.config.ScriptConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 工具调用 Rail。
 *
 * @since 2024-01-01
 *
 */

public class McpInterruptRail extends AgentRail {
    static final String DEFAULT_MCP_PRODUCTS_KEY = "mcp_products_data";
    static final String VERSATILE_QUERY_KEY = "mcp_to_versatile_information";
    static final String HISTORY_INFO_KEY = "history_info";
    static final String HISTORY_PARAMS_KEY = "history_params";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpInterruptRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60);

    private final EdpConfig edpConfig;
    private final ToolDataChannel toolDataChannel;
    private final Path skillsDir;
    private final EdpaSpringBootConfig springBootConfig;
    private final String agentName;

    /**
     * 框架双模式门面（可为 null，sandbox.enabled=false 时使用原有 ProcessBuilder）。
     */
    private final SysOperation sysOp;

    /**
     * 沙箱技能部署路径（如 /app/skills），SANDBOX 模式下作为 cwd 参数传入 executeCmd。可为 null。
     */
    private final String skillDeployPath;

    /**
     * 治理装饰 SandboxClient（需求2路径，可为 null）。非 null 时在 SANDBOX 模式优先使用其 shell()。
     */
    private final SandboxClient decoratedClient;

    public McpInterruptRail(EdpConfig edpConfig) {
        this(edpConfig, new ToolDataChannel(), null, null, "EDPAgent", null, null, null);
    }

    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel) {
        this(edpConfig, toolDataChannel, null, null, "EDPAgent", null, null, null);
    }

    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir) {
        this(edpConfig, toolDataChannel, skillsDir, null, "EDPAgent", null, null, null);
    }

    // 4参数版（兼容旧调用）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig) {
        this(edpConfig, toolDataChannel, skillsDir, springBootConfig, "EDPAgent", null, null, null);
    }

    // 5参数版（agentName）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig, String agentName) {
        this(edpConfig, toolDataChannel, skillsDir, springBootConfig, agentName, null, null, null);
    }

    // 5参数版（sysOp, sandbox original）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig, SysOperation sysOp) {
        this(edpConfig, toolDataChannel, skillsDir, springBootConfig, "EDPAgent", sysOp, null, null);
    }

    // 6参数版（agentName + sysOp, legacy）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig, String agentName, SysOperation sysOp) {
        this(edpConfig, toolDataChannel, skillsDir, springBootConfig, agentName, sysOp, null, null);
    }

    // 7参数版（含 skillDeployPath）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig, String agentName, SysOperation sysOp, String skillDeployPath) {
        this(edpConfig, toolDataChannel, skillsDir, springBootConfig, agentName, sysOp, skillDeployPath, null);
    }

    // 8参数版（全参构造，含 skillDeployPath + decoratedClient）
    public McpInterruptRail(EdpConfig edpConfig, ToolDataChannel toolDataChannel, Path skillsDir,
            EdpaSpringBootConfig springBootConfig, String agentName, SysOperation sysOp, String skillDeployPath,
            SandboxClient decoratedClient) {
        this.edpConfig = edpConfig;
        this.toolDataChannel = toolDataChannel != null ? toolDataChannel : new ToolDataChannel();
        this.skillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize() : null;
        this.springBootConfig = springBootConfig;
        this.agentName = agentName != null ? agentName : "EDPAgent";
        this.sysOp = sysOp;
        this.skillDeployPath = skillDeployPath;
        this.decoratedClient = decoratedClient;
        setPriority(85);
    }

    @Override
    /**
     * Before tool call.
     *
     * @param ctx the ctx value
     */
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        if (!"call_mcp".equals(toolName)) {
            return;
        }

        LOGGER.info("[MCPInterruptRail] intercept call_mcp: toolArgsKeys={ // no-op }", normalizeArgs(inputs).keySet());
        ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
        Map<String, Object> result = executeMcpScript(inputs, ctx);
        inputs.setToolResult(result);
        inputs.setToolMsg(ToolMessage.builder().content(toJson(result))
                .toolCallId(inputs.getToolCall() != null ? inputs.getToolCall().getId() : "call_mcp").build());
    }

    @Override
    /**
     * After tool call.
     *
     * @param ctx the ctx value
     */
    public void afterToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        if (!"call_mcp".equals(toolName)) {
            return;
        }

        LOGGER.info("McpInterruptRail: call_mcp completed, result validated");
        Map<String, Object> result = normalizeResult(inputs);
        if (result.isEmpty()) {
            LOGGER.debug("McpInterruptRail: call_mcp result is empty, skip ToolDataChannel write");
            return;
        }
        persistMcpResult(ctx, result);
        updateToolMessage(inputs, result);
    }

    void persistMcpResult(AgentCallbackContext ctx, Map<String, Object> result) {
        ToolDataKey key = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        String resultKey = asString(result.get("result_key"));
        if (isBlank(resultKey)) {
            resultKey = DEFAULT_MCP_PRODUCTS_KEY;
        }

        Map<String, Object> data = removeControlFields(result);
        toolDataChannel.store(key, resultKey, data);
        if (!DEFAULT_MCP_PRODUCTS_KEY.equals(resultKey)) {
            toolDataChannel.store(key, DEFAULT_MCP_PRODUCTS_KEY, data);
        }
        LOGGER.info("McpInterruptRail: stored call_mcp result to ToolDataChannel key={ // no-op }, resultKey={ // no-op }, fields={ // no-op }", key,
                resultKey, data.keySet());

        Object versatileQuery = result.get("versatile_query");
        if (versatileQuery instanceof String text && !text.isBlank()) {
            toolDataChannel.store(key, VERSATILE_QUERY_KEY, Map.of("query_description", text));
            result.put("versatile_query", "");
            LOGGER.info("McpInterruptRail: cached versatile_query to ToolDataChannel key={ // no-op }", key);
        }

        if (result.containsKey(HISTORY_INFO_KEY)) {
            toolDataChannel.store(key, HISTORY_INFO_KEY, Map.of("value", result.get(HISTORY_INFO_KEY)));
        }
        Object historyParams = result.get(HISTORY_PARAMS_KEY);
        if (historyParams instanceof Map<?, ?> map) {
            toolDataChannel.store(key, HISTORY_PARAMS_KEY, toStringKeyMap(map));
        }
    }

    private Map<String, Object> executeMcpScript(ToolCallInputs inputs, AgentCallbackContext ctx) {
        Map<String, Object> args = normalizeArgs(inputs);
        String scriptCommand = asString(args.get("script_command"));
        if (isBlank(scriptCommand)) {
            return failedResult("script_command is blank");
        }

        Map<String, Object> scriptParams = normalizeArgsObject(args.get("script_params"));
        LOGGER.info("[MCPInterruptRail] script_command='{ // no-op }', scriptParamsKeys={ // no-op }", abbreviate(scriptCommand, 80),
                scriptParams.keySet());
        Map<String, Object> skillInput = buildSkillInput(scriptParams, ctx);
        if (skillInput != null) {
            scriptParams.put("SKILL_INPUT", skillInput);
        }
        String argumentsJson = toJson(skillInput);
        List<String> command = buildCommand(scriptCommand);
        Path workDir = resolveWorkDir(command).orElse(null);

        // SandboxInterruptRail 在 SANDBOX 模式下已拦截 call_mcp 工具调用并 reject 结果，
        // 此方法仅在以下情况执行：
        // 1. SandboxInterruptRail LOCAL 模式 approve() 后放行
        // 2. SandboxInterruptRail SANDBOX 执行失败降级 approve() 后放行
        // 3. SandboxInterruptRail 未注册（sandbox.enabled=false）
        if (sysOp != null) {
            return executeViaSysOperation(scriptCommand, argumentsJson, scriptParams,
                    workDir != null ? workDir.toString() : ".");
        }

        return executeViaProcessBuilder(command, workDir, argumentsJson, scriptParams);
    }

    /**
     * Executes the MCP script via ProcessBuilder (local mode).
     *
     * @param command the command value
     * @param workDir the workDir value
     * @param argumentsJson the argumentsJson value
     * @param scriptParams the scriptParams value
     * @return the result
     */
    private Map<String, Object> executeViaProcessBuilder(List<String> command, Path workDir, String argumentsJson,
            Map<String, Object> scriptParams) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workDir != null) {
                builder.directory(workDir.toFile());
            }
            builder.environment().put("SKILL_INPUT", argumentsJson);
            builder.environment().put("PYTHONIOENCODING", "utf-8");

            // ---- MCP SSE 配置注入 + 灰度路由 ----
            String wapGrayFlag = extractWapGrayFlag(scriptParams).orElse(null);
            if (springBootConfig != null && springBootConfig.getMcpsse() != null) {
                var mcpConfig = springBootConfig.getMcpsse();
                String mcpServerUrl = (wapGrayFlag != null && wapGrayFlag.startsWith("JD"))
                        ? mcpConfig.getMasterUrl()
                        : mcpConfig.getStandbyUrl();
                if (mcpServerUrl != null) {
                    builder.environment().put("MCP_SERVER_URL", mcpServerUrl);
                }
                if (mcpConfig.getAccessToken() != null) {
                    builder.environment().put("MCP_ACCESS_TOKEN", mcpConfig.getAccessToken());
                }
                if (mcpConfig.getAppName() != null) {
                    builder.environment().put("MCP_APP_NAME", mcpConfig.getAppName());
                }
                LOGGER.info(
                        "[MCPInterruptRail] MCP SSE env injected, wapGrayFlag={ // no-op }, serverUrl={ // no-op }, "
                                + "hasAccessToken={ // no-op }, appName={ // no-op }",
                        wapGrayFlag, mcpServerUrl, mcpConfig.getAccessToken() != null, mcpConfig.getAppName());
            }

            // ---- MCP SSE 配置注入结束 ----

            LOGGER.info("McpInterruptRail: execute script command={ // no-op }, workDir={ // no-op }", command, workDir);

            Process process = builder.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Future<?> stdoutFuture = readAsync(process.getInputStream(), stdout);
            Future<?> stderrFuture = readAsync(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(SCRIPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return failedResult("MCP script timeout after " + SCRIPT_TIMEOUT.toSeconds() + "s");
            }
            try {
                stdoutFuture.get(1, TimeUnit.SECONDS);
                stderrFuture.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                LOGGER.debug("McpInterruptRail: stream read timeout/interrupt", e);
            }

            int exitCode = process.exitValue();
            LOGGER.info("[MCPInterruptRail] local script executed: exitCode={ // no-op }, stdoutLen={ // no-op }, stderrLen={ // no-op }", exitCode,
                    stdout.length(), abbreviate(stderr.toString()));
            if (exitCode != 0) {
                return failedResult("MCP script exitCode=" + exitCode + ", stderr=" + abbreviate(stderr.toString()));
            }
            return parseScriptOutput(stdout.toString());
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOGGER.warn("McpInterruptRail: local script execution failed: { // no-op }", e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    /**
     * 构造传给沙箱脚本的 SKILL_INPUT。
     *
     * <p>对齐 Python MCPInterruptRail._build_skill_input：合并 script_params 与
     * 从 ToolDataChannel 读取的持久化字段（history_info / history_params），
     * 持久化字段覆盖 LLM 可能传入的同名字段，避免 LLM 搬运导致的截断或遗漏风险。
     * mcp_required_params 在 Java 端由 LLM 通过 script_params 传入（设计与 Python 不同，
     * Python 从 session.state["original_body"] 读取以避免经过 LLM）。</p>
     *
     * @param scriptParams the scriptParams value
     * @param ctx the ctx value
     * @return the result
     */

    private Map<String, Object> buildSkillInput(Map<String, Object> scriptParams, AgentCallbackContext ctx) {
        Map<String, Object> skillInput = new LinkedHashMap<>(scriptParams);

        ToolDataKey key = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        Object historyInfo = toolDataChannel.getObject(key, HISTORY_INFO_KEY).orElse(null);
        if (historyInfo instanceof Map<?, ?> map && map.containsKey("value")) {
            skillInput.put(HISTORY_INFO_KEY, map.get("value"));
        } else if (historyInfo != null) {
            skillInput.put(HISTORY_INFO_KEY, historyInfo);
        } else {
            skillInput.putIfAbsent(HISTORY_INFO_KEY, List.of());
        }

        Object historyParams = toolDataChannel.getObject(key, HISTORY_PARAMS_KEY).orElse(null);
        if (historyParams instanceof Map<?, ?> map) {
            skillInput.put(HISTORY_PARAMS_KEY, toStringKeyMap(map));
        } else if (historyParams != null) {
            skillInput.put(HISTORY_PARAMS_KEY, historyParams);
        } else {
            skillInput.putIfAbsent(HISTORY_PARAMS_KEY, Map.of());
        }

        LOGGER.info("McpInterruptRail: injected from ToolDataChannel key={ // no-op }, history_info={ // no-op }, history_params={ // no-op }", key,
                abbreviate(String.valueOf(skillInput.get(HISTORY_INFO_KEY))),
                abbreviate(String.valueOf(skillInput.get(HISTORY_PARAMS_KEY))));
        LOGGER.debug("[MCPInterruptRail] history injected: historyInfoType={ // no-op }, historyParamsType={ // no-op }",
                historyInfo != null ? historyInfo.getClass().getSimpleName() : "null",
                historyParams != null ? historyParams.getClass().getSimpleName() : "null");
        return skillInput;
    }

    private Future<?> readAsync(java.io.InputStream inputStream, StringBuilder target) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) -> {
                LOGGER.error("McpInterruptRail: unexpected error in async read thread", e);
            });
            return t;
        });
        return executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (target.length() > 0) {
                        target.append(System.lineSeparator());
                    }
                    target.append(line);
                }
            } catch (IOException e) {
                LOGGER.debug("McpInterruptRail: failed to read process stream", e);
            } finally {
                executor.shutdown();
            }
        });
    }

    private List<String> buildCommand(String scriptCommand) {
        List<String> tokens = splitCommand(scriptCommand);
        if (tokens.isEmpty()) {
            return List.of();
        }
        if (tokens.size() >= 2 && isPythonCommand(tokens.get(0))) {
            tokens.set(1, resolveScriptPath(tokens.get(1)).toString());
        } else {
            tokens.set(0, resolveScriptPath(tokens.get(0)).toString());
        }
        return tokens;
    }

    private Optional<Path> resolveWorkDir(List<String> command) {
        if (command.isEmpty()) {
            return Optional.empty();
        }
        int scriptIndex = command.size() >= 2 && isPythonCommand(command.get(0)) ? 1 : 0;
        Path scriptPath = Path.of(command.get(scriptIndex));
        return Optional.ofNullable(scriptPath.getParent());
    }

    private Path resolveScriptPath(String scriptPath) {
        Path path = Path.of(scriptPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        if (skillsDir != null) {
            Path resolved = skillsDir.resolve(scriptPath).normalize();
            if (Files.exists(resolved)) {
                LOGGER.debug("[MCPInterruptRail] script path resolved via skillsDir: { // no-op }", resolved);
                return resolved;
            }
            LOGGER.debug("[MCPInterruptRail] script path not found under skillsDir: { // no-op }", skillsDir);
        }
        Path cwdResolved = Path.of("").toAbsolutePath().normalize().resolve(scriptPath).normalize();
        if (Files.exists(cwdResolved)) {
            LOGGER.debug("[MCPInterruptRail] script path resolved via CWD: { // no-op }", cwdResolved);
            return cwdResolved;
        }
        Path defaultSkillsResolved = Path.of("").toAbsolutePath().normalize().resolve("../scenarios/wealth-demo/skills")
                .resolve(scriptPath).normalize();

        // 降级说明：脚本路径可能在默认路径不存在，走兜底回退
        LOGGER.warn("[MCPInterruptRail] script path falling back to default (may not exist): { // no-op }",
                defaultSkillsResolved);
        return defaultSkillsResolved;
    }

    private boolean isPythonCommand(String command) {
        String value = command.toLowerCase(Locale.ROOT);
        return "python".equals(value) || "python3".equals(value) || value.endsWith("python.exe");
    }

    private List<String> splitCommand(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if ((ch == '\'' || ch == '"')) {
                if (inQuote && ch == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    inQuote = true;
                    quoteChar = ch;
                } else {
                    current.append(ch);
                }
            } else if (Character.isWhitespace(ch) && !inQuote) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private Map<String, Object> parseScriptOutput(String stdout) {
        String json = lastJsonLine(stdout);
        if (isBlank(json)) {
            return failedResult("MCP script stdout is empty");
        }
        try {
            Map<String, Object> result = toStringKeyMap(OBJECT_MAPPER.readValue(json, Map.class));
            result.putIfAbsent("result_key", DEFAULT_MCP_PRODUCTS_KEY);
            return result;
        } catch (JsonProcessingException e) {
            LOGGER.warn("McpInterruptRail: failed to parse script stdout JSON: { // no-op }", abbreviate(stdout));
            return failedResult("failed to parse MCP script stdout JSON: " + e.getMessage());
        }
    }

    static String lastJsonLine(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }
        String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        return stdout.trim();
    }

    private Map<String, Object> normalizeResult(ToolCallInputs inputs) {
        Map<String, Object> result = normalizeObject(inputs.getToolResult());
        if (!result.isEmpty()) {
            return result;
        }
        ToolMessage toolMsg = inputs.getToolMsg();
        return toolMsg != null ? normalizeObject(toolMsg.getContent()) : Map.of();
    }

    private Map<String, Object> normalizeObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (node != null && node.isObject()) {
                    return toStringKeyMap(OBJECT_MAPPER.convertValue(node, Map.class));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("McpInterruptRail: failed to parse call_mcp result JSON: { // no-op }", abbreviate(text));
            }
        }
        return Map.of();
    }

    private Map<String, Object> normalizeArgs(ToolCallInputs inputs) {
        Map<String, Object> args = normalizeArgsObject(inputs.getToolArgs());
        if (args.isEmpty() && inputs.getToolCall() != null) {
            args = normalizeArgsObject(inputs.getToolCall().getArguments());
        }
        return args;
    }

    private Map<String, Object> normalizeArgsObject(Object toolArgs) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (toolArgs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
        if (toolArgs instanceof String text && !text.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(entry -> args.put(entry.getKey(),
                            OBJECT_MAPPER.convertValue(entry.getValue(), Object.class)));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("McpInterruptRail: failed to parse tool arguments: { // no-op }", text);
            }
        }
        return args;
    }

    private Map<String, Object> removeControlFields(Map<String, Object> result) {
        Map<String, Object> data = new LinkedHashMap<>(result);
        data.remove("result_key");
        data.remove("versatile_query");
        data.remove("ui_notice");
        data.remove("response_template");
        return data;
    }

    private void updateToolMessage(ToolCallInputs inputs, Map<String, Object> result) {
        if (inputs.getToolMsg() == null) {
            return;
        }
        try {
            inputs.setToolMsg(ToolMessage.builder().content(OBJECT_MAPPER.writeValueAsString(result))
                    .toolCallId(inputs.getToolMsg().getToolCallId()).build());
        } catch (JsonProcessingException e) {
            LOGGER.debug("McpInterruptRail: failed to refresh tool message after control field cleanup", e);
        }
    }

    /**
     * Builds a failed result map with the given message.
     *
     * @param message the message value
     * @return the result
     */
    public static Map<String, Object> failedResult(String message) {
        // 对齐 Python MCPInterruptRail._build_error_result：失败时清空 history_info 和 history_params，
        // 使下次调用不继承上次条件；不含 versatile_query（Python 端失败时不注入此字段）。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "failed");
        result.put("tool", "call_mcp");
        result.put("mcp_error", message != null ? message : "unknown error");
        result.put("products", List.of());
        result.put("total", 0);
        result.put("next_sort_type", 0);
        result.put("history_params", Map.of());
        result.put("history_info", List.of());
        result.put("result_key", DEFAULT_MCP_PRODUCTS_KEY);
        return result;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String abbreviate(String value) {
        return abbreviate(value, 500);
    }

    private String abbreviate(String value, int maxLen) {
        return value != null && value.length() > maxLen ? value.substring(0, maxLen) + "...(truncated)" : value;
    }

    /**
     * 从 script_params 中提取 wap_grayFlag。
     *
     * <p>mcp_required_params 在运行时可能是 String（Python dict repr 单引号格式）
     * 或已解析的 Map&lt;String,Object&gt;。两种类型均需处理。</p>
     *
     * @param scriptParams the scriptParams value
     * @return the result
     */

    private Optional<String> extractWapGrayFlag(Map<String, Object> scriptParams) {
        Object mcpRequired = scriptParams.get("mcp_required_params");
        if (mcpRequired instanceof String mcpRequiredStr) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("wap_grayFlag['\"]\\s*:\\s*['\"]([^'\"]+)")
                    .matcher(mcpRequiredStr);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
        } else if (mcpRequired instanceof Map<?, ?> mcpRequiredMap) {
            return extractWapGrayFlagFromMap(mcpRequiredMap);
        } else {
            // no-op: mcp_required_params is neither String nor Map
        }
        return Optional.empty();
    }

    /**
     * Extracts wap_grayFlag from a Map-typed mcp_required_params.
     *
     * @param mcpRequiredMap the mcpRequiredMap value
     * @return the result
     */
    private Optional<String> extractWapGrayFlagFromMap(Map<?, ?> mcpRequiredMap) {
        Object customData = mcpRequiredMap.get("custom_data");
        if (!(customData instanceof Map<?, ?> cd)) {
            return Optional.empty();
        }
        Object inputs = cd.get("inputs");
        if (!(inputs instanceof Map<?, ?> in)) {
            return Optional.empty();
        }
        Object flag = in.get("wap_grayFlag");
        return flag != null ? Optional.of(flag.toString()) : Optional.empty();
    }

    // ===== 沙箱路由方法 =====

    /**
     * 通过 SysOperation 统一入口执行脚本（LOCAL 模式 → LocalShellOperation，含白名单+危险命令拦截）。
     *
     * @param scriptCommand the scriptCommand value
     * @param argumentsJson the argumentsJson value
     * @param scriptParams the scriptParams value
     * @param workDir the workDir value
     * @return the result
     */
    private Map<String, Object> executeViaSysOperation(String scriptCommand, String argumentsJson,
            Map<String, Object> scriptParams, String workDir) {
        try {
            Map<String, String> env = buildEnvMap(argumentsJson, scriptParams);
            String command;
            String cwd;

            if (sysOp != null && sysOp.getMode() == OperationMode.SANDBOX && skillDeployPath != null) {
                // SANDBOX模式：保持 scriptCommand 为 LLM 传入的相对路径，
                // 通过 cwd 参数设置沙箱内工作目录（利用框架原生 cwd 机制，无需 cd 命令）
                command = scriptCommand;
                cwd = skillDeployPath; // "/app/skills"
            } else {
                // LOCAL模式或 skillDeployPath 未配置：走原有 buildScriptCommand 逻辑（解析为绝对路径）
                command = buildScriptCommand(scriptCommand, skillsDir);

                // Windows 下将 workDir 中的反斜杠统一为正斜杠，避免传递过程中的转义问题
                cwd = workDir != null ? workDir.replace('\\', '/') : ".";
            }

            LOGGER.info("McpInterruptRail: execute via SysOperation, command={ // no-op }, cwd={ // no-op }, governed={ // no-op }", command, cwd,
                    decoratedClient != null);

            // 脚本执行结果日志将在 adaptCmdResult 方法中输出

            // 核心修改：SANDBOX 模式优先使用 decoratedClient（需求2路径：经过治理装饰）
            ExecuteCmdResult result;
            if (decoratedClient != null && sysOp != null && sysOp.getMode() == OperationMode.SANDBOX
                    && skillDeployPath != null) {
                result = decoratedClient.shell().executeCmd(command, cwd, (int) SCRIPT_TIMEOUT.toSeconds(), env, null);
            } else {
                result = sysOp.shell().executeCmd(command, cwd, (int) SCRIPT_TIMEOUT.toSeconds(), env, null);
            }
            return adaptCmdResult(result);
        } catch (IllegalStateException e) {
            LOGGER.warn("McpInterruptRail: SysOperation execution failed: { // no-op }, falling back to ProcessBuilder",
                    e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    /**
     * 适配 ExecuteCmdResult → McpInterruptRail.parseScriptOutput 兼容的 Map 格式。
     *
     * @param result the result value
     * @return the result
     */
    private Map<String, Object> adaptCmdResult(ExecuteCmdResult result) {
        if (result == null || result.getData() == null) {
            return failedResult("SysOperation returned null result");
        }
        int exitCode = result.getData().getExitCode() != null ? result.getData().getExitCode() : -1;
        String stdout = result.getData().getStdout() != null ? result.getData().getStdout() : "";
        String stderr = result.getData().getStderr() != null ? result.getData().getStderr() : "";

        LOGGER.info("[MCPInterruptRail] sandbox executed: exitCode={ // no-op }, stdoutLen={ // no-op }, stderrLen={ // no-op }", exitCode,
                stdout.length(), stderr.length());

        if (exitCode != 0) {
            return failedResult("exitCode=" + exitCode + ", stderr=" + abbreviate(stderr));
        }
        return parseScriptOutput(stdout);
    }

    /**
     * 构建环境变量 Map（SKILL_INPUT + PYTHONIOENCODING + MCP SSE 配置）。
     *
     * @param argumentsJson the argumentsJson value
     * @param scriptParams the scriptParams value
     * @return the result
     */
    private Map<String, String> buildEnvMap(String argumentsJson, Map<String, Object> scriptParams) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SKILL_INPUT", argumentsJson);
        env.put("PYTHONIOENCODING", "utf-8");

        // MCP SSE 配置注入
        String wapGrayFlag = extractWapGrayFlag(scriptParams).orElse(null);
        if (springBootConfig != null && springBootConfig.getMcpsse() != null) {
            var mcpConfig = springBootConfig.getMcpsse();
            String mcpServerUrl = (wapGrayFlag != null && wapGrayFlag.startsWith("JD"))
                    ? mcpConfig.getMasterUrl()
                    : mcpConfig.getStandbyUrl();
            if (mcpServerUrl != null) {
                env.put("MCP_SERVER_URL", mcpServerUrl);
            }
            if (mcpConfig.getAccessToken() != null) {
                env.put("MCP_ACCESS_TOKEN", mcpConfig.getAccessToken());
            }
            if (mcpConfig.getAppName() != null) {
                env.put("MCP_APP_NAME", mcpConfig.getAppName());
            }
            LOGGER.info(
                    "[MCPInterruptRail] MCP SSE env injected via SysOperation, wapGrayFlag={ // no-op }, "
                            + "serverUrl={ // no-op }, hasAccessToken={ // no-op }, appName={ // no-op }",
                    wapGrayFlag, mcpServerUrl, mcpConfig.getAccessToken() != null, mcpConfig.getAppName());
        }
        LOGGER.debug("[MCPInterruptRail] env constructed: keys={ // no-op }, hasMCPUrl={ // no-op }, hasAccessToken={ // no-op }", env.keySet(),
                env.containsKey("MCP_SERVER_URL"), env.containsKey("MCP_ACCESS_TOKEN"));
        return env;
    }

    /**
     * 当路径包含空格或特殊字符时，添加双引号包裹。
     *
     * @param path the path value
     * @return the result
     */
    private static String quoteIfNecessary(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        // 已经有引号则不再添加
        if (path.startsWith("\"") && path.endsWith("\"")) {
            return path;
        }

        // 包含空格、括号等特殊字符时需要引号
        if (path.contains(" ") || path.contains("(") || path.contains(")")) {
            return "\"" + path + "\"";
        }
        return path;
    }

    // ===== 公开静态辅助方法（供 SandboxInterruptRail 调用） =====

    /**
     * Extract script args.
     *
     * @param inputs the inputs value
     * @return the result
     */
    public static Map<String, Object> extractScriptArgs(ToolCallInputs inputs) {
        Map<String, Object> args = normalizeArgsObjectStatic(inputs.getToolArgs());
        if (args.isEmpty() && inputs.getToolCall() != null) {
            args = normalizeArgsObjectStatic(inputs.getToolCall().getArguments());
        }
        return args;
    }

    /**
     * Extract script params.
     *
     * @param args the args value
     * @return the result
     */
    public static Map<String, Object> extractScriptParams(Map<String, Object> args) {
        return normalizeArgsObjectStatic(args.get("script_params"));
    }

    /**
     * Build script command.
     *
     * @param scriptCommand the scriptCommand value
     * @return the result
     */
    public static String buildScriptCommand(String scriptCommand) {
        return buildScriptCommand(scriptCommand, null);
    }

    /**
     * 构建脚本命令字符串（含 skillsDir 的内部版，供实例方法调用）。
     *
     * @param scriptCommand the scriptCommand value
     * @param skillsDir the skillsDir value
     * @return the result
     */
    static String buildScriptCommand(String scriptCommand, Path skillsDir) {
        List<String> tokens = splitCommandStatic(scriptCommand);
        if (tokens.isEmpty()) {
            return scriptCommand;
        }
        if (tokens.size() >= 2 && isPythonCommandStatic(tokens.get(0))) {
            String resolved = resolveScriptPathStatic(tokens.get(1), skillsDir).toString().replace('\\', '/');
            tokens.set(1, quoteIfNecessary(resolved));
        } else {
            String resolved = resolveScriptPathStatic(tokens.get(0), skillsDir).toString().replace('\\', '/');
            tokens.set(0, quoteIfNecessary(resolved));
        }
        return String.join(" ", tokens);
    }

    /**
     * Resolve script work dir.
     *
     * @param scriptCommand the scriptCommand value
     * @return the result
     */
    public static String resolveScriptWorkDir(String scriptCommand) {
        return resolveScriptWorkDir(scriptCommand, null);
    }

    /**
     * 解析脚本所在工作目录（含 skillsDir 的内部版）。
     *
     * @param scriptCommand the scriptCommand value
     * @param skillsDir the skillsDir value
     * @return the result
     */
    static String resolveScriptWorkDir(String scriptCommand, Path skillsDir) {
        List<String> tokens = splitCommandStatic(scriptCommand);
        if (tokens.isEmpty()) {
            return ".";
        }
        int scriptIndex = tokens.size() >= 2 && isPythonCommandStatic(tokens.get(0)) ? 1 : 0;
        Path scriptPath = resolveScriptPathStatic(tokens.get(scriptIndex), skillsDir);
        Path parent = scriptPath.getParent();
        return parent != null ? parent.toString() : ".";
    }

    // ===== 静态辅助方法（公开版的实现委托） =====

    private static Map<String, Object> normalizeArgsObjectStatic(Object toolArgs) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (toolArgs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
        if (toolArgs instanceof String text && !text.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(entry -> args.put(entry.getKey(),
                            OBJECT_MAPPER.convertValue(entry.getValue(), Object.class)));
                }
            } catch (JsonProcessingException e) {
                // 对齐 Python mcp_interrupt_rail.py L203-206: WARNING script_params 解码后非 dict
                LOGGER.warn("[MCPInterruptRail] normalizeArgsObjectStatic failed to parse toolArgs JSON: { // no-op }", text);
            }
        }
        return args;
    }

    private static List<String> splitCommandStatic(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if ((ch == '\'' || ch == '"')) {
                if (inQuote && ch == quoteChar) {
                    inQuote = false;
                } else if (!inQuote) {
                    inQuote = true;
                    quoteChar = ch;
                } else {
                    current.append(ch);
                }
            } else if (Character.isWhitespace(ch) && !inQuote) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private static boolean isPythonCommandStatic(String command) {
        String value = command.toLowerCase(Locale.ROOT);
        return "python".equals(value) || "python3".equals(value) || value.endsWith("python.exe");
    }

    /**
     * 按优先级回退查找脚本路径：绝对路径 → skillsDir → CWD → 默认路径。
     * 对齐 Python mcp_interrupt_rail.py L310: 执行脚本 cd {skills_dir} && {script_command}。
     *
     * @param scriptPath 脚本相对路径
     * @param skillsDir  skills 目录绝对路径
     * @return 解析后的脚本路径（可能不存在）
     *
     */

    private static Path resolveScriptPathStatic(String scriptPath, Path skillsDir) {
        Path path = Path.of(scriptPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        if (skillsDir != null) {
            Path resolved = skillsDir.resolve(scriptPath).normalize();
            if (Files.exists(resolved)) {
                return resolved;
            }
        }
        Path cwdResolved = Path.of("").toAbsolutePath().normalize().resolve(scriptPath).normalize();
        if (Files.exists(cwdResolved)) {
            return cwdResolved;
        }
        Path defaultSkillsResolved = Path.of("").toAbsolutePath().normalize().resolve("../scenarios/wealth-demo/skills")
                .resolve(scriptPath).normalize();
        return defaultSkillsResolved;
    }
}
