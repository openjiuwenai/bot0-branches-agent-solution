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

package com.openjiuwen.edp.rail;

import com.openjiuwen.edp.channel.ToolDataChannel;
import com.openjiuwen.edp.channel.ToolDataKey;
import com.openjiuwen.edp.channel.ToolDataKeyFactory;
import com.openjiuwen.edp.config.EdpConfig;
import com.openjiuwen.edp.config.EdpaSpringBootConfig.VersatileConfig;
import com.openjiuwen.edp.config.ScriptConstants;
import com.openjiuwen.edp.config.SysScriptsConfig;
import com.openjiuwen.edp.tools.EdpaBusinessTools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 通用子智能体委派 Rail。
 *
 * <p>与 {@link VersatileDelegateRail} 的差异：不硬编码 {@code agentName}，
 * 而是从 {@code call_subagent} 工具参数中读取 {@code agent_name}，
 * 使同一 Rail 可委托给不同子智能体。其余逻辑（guard、熔断、归一化、history_info、脱敏）完全一致。</p>
 *
 * @since 2026-08-14
 */

public class SubagentDelegateRail extends BaseInterruptRail {
    /** pre-delegate guard 计数器在 ToolDataChannel 中的 state_key 前缀。 */
    static final String GUARD_STATE_KEY_PREFIX = "_pre_delegate_guard:";

    /** history_info 持久化键（与 McpInterruptRail 同名，共享四元组隔离）。 */
    static final String HISTORY_INFO_KEY = "history_info";

    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentDelegateRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> LINKED_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * afterToolCall 归一化挂载标志。
     *
     * <p>框架在 interrupt 分支（抛 ToolInterruptException）后<b>仍然会调用</b> afterToolCall，
     * 但此时 toolResult 是中断态的中间值，不应归一化。仅在 reject(resumeInput) 续传恢复分支
     * 设置此标志，afterToolCall 检测到 true 才执行归一化并清除标志。</p>
     */
    private static final String KEY_NORMALIZE_PENDING = "_edp_subagent_normalize_pending";

    /** 银联卡号正则：以 62 开头的 16-19 位连续数字。 */
    private static final Pattern BANK_CARD_NUMBER_PATTERN = Pattern.compile("(62\\d{14,17})");

    private final EdpConfig edpConfig;
    private final VersatileConfig versatileConfig;
    private final ToolDataChannel toolDataChannel;
    private final Path skillsDir;
    private final SysScriptsConfig scripts;
    private final String agentName;
    private final SysOperation sysOp;
    private final String skillDeployPath;
    private final SandboxClient decoratedClient;
    private final CircuitBreaker circuitBreaker;

    /**
     * 全参构造（由 EdpaAgentEnhancer 注入）。
     *
     * @param edpConfig EDP 配置（用于 ToolDataKey 四元组隔离）
     * @param versatileConfig 服务配置（含熔断器配置）
     * @param toolDataChannel 会话级数据通道（guard 计数 / history_info）
     * @param skillsDir 技能脚本目录（归一化脚本 / guard 脚本所在）
     * @param scripts 话术模板配置
     * @param agentName Agent 名称（用于 ToolDataKey 隔离，非远端目标）
     * @param sysOp 系统操作门面（SANDBOX 模式归一化脚本执行）
     * @param skillDeployPath 沙箱中脚本部署路径
     * @param decoratedClient 治理装饰的 SandboxClient
     */
    public SubagentDelegateRail(EdpConfig edpConfig, VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, Path skillsDir, SysScriptsConfig scripts, String agentName,
            SysOperation sysOp, String skillDeployPath, SandboxClient decoratedClient) {
        super(java.util.List.of(EdpaBusinessTools.TOOL_CALL_SUBAGENT));
        this.edpConfig = edpConfig;
        this.versatileConfig = versatileConfig;
        this.toolDataChannel = toolDataChannel != null ? toolDataChannel : new ToolDataChannel();
        this.skillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize() : null;
        this.scripts = scripts;
        this.agentName = agentName;
        this.sysOp = sysOp;
        this.skillDeployPath = skillDeployPath;
        this.decoratedClient = decoratedClient;

        if (versatileConfig != null && versatileConfig.getCircuitBreaker() != null
                && versatileConfig.getCircuitBreaker().isEnabled()) {
            var cbConfig = versatileConfig.getCircuitBreaker();
            this.circuitBreaker = new CircuitBreaker("subagent",
                    cbConfig.getFailureThreshold(), cbConfig.getResetTimeoutMs());
            LOGGER.info("[SubagentDelegateRail] circuit breaker enabled: failureThreshold={}, resetTimeoutMs={}",
                    cbConfig.getFailureThreshold(), cbConfig.getResetTimeoutMs());
        } else {
            this.circuitBreaker = null;
        }

        setPriority(85);
        LOGGER.info("SubagentDelegateRail initialized, intercepting tool={}",
                EdpaBusinessTools.TOOL_CALL_SUBAGENT);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveInterrupt：委派决策 + guard + 熔断前置
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        Map<String, Object> args = parseToolArgs(toolCall);

        // ── 续传路径：框架已通过 a2a_delegate 续传拿到远端结果，直接 reject 回喂 ──
        if (resumeInput != null) {
            LOGGER.info("SubagentDelegateRail: resume input received, rejecting as tool result");
            ctx.getExtra().put(KEY_NORMALIZE_PENDING, Boolean.TRUE);
            return reject(resumeInput);
        }

        // ── pre-delegate guard：超限则 reject 降级结果 + requestForceFinish ──
        Optional<GuardDecision> guard = applyPreDelegateGuard(ctx, args);
        if (guard.isPresent() && guard.get().blocked()) {
            GuardDecision d = guard.get();
            LOGGER.info("[SubagentDelegateRail] guard blocked: rule={}, count={}, limit={}",
                    d.ruleId(), d.count(), d.maxCalls());
            ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, d.message());
            ctx.requestForceFinish(Map.of("result_type", "interrupt", "state", List.of(),
                    "interrupt_ids", List.of()));
            Map<String, Object> blockedResult = new LinkedHashMap<>();
            blockedResult.put("status", "failed");
            blockedResult.put("message", d.message());
            ctx.getExtra().put(KEY_NORMALIZE_PENDING, Boolean.FALSE);
            return reject(blockedResult);
        }

        // ── 熔断器前置检查：OPEN 状态快速失败，避免远端调用 ──
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            String convId = resolveConversationId(ctx);
            LOGGER.warn("[SubagentDelegateRail] circuit breaker OPEN, returning degraded response, convId={}",
                    convId);
            ctx.getExtra().put(KEY_NORMALIZE_PENDING, Boolean.FALSE);
            return reject(failedResult("智能体暂不可用，请稍后重试"));
        }

        // ── 正常委派：从参数读取 agent_name，构造 a2a_delegate 中断 ──
        injectCachedQueryDescription(args, ctx);
        String remoteInput = extractRemoteInput(toolCall, args);
        String queryIntent = asString(args.get("query_intent"));
        String remoteAgentName = asString(args.get("agent_name"));
        if (remoteAgentName.isBlank()) {
            LOGGER.warn("[SubagentDelegateRail] agent_name is empty, falling back to versatile-agent");
            remoteAgentName = VersatileDelegateRail.REMOTE_AGENT_NAME;
        }
        LOGGER.info("[SubagentDelegateRail] delegating to agent='{}', queryIntent='{}', remoteInputLen={}",
                remoteAgentName, abbreviate(desensitize(queryIntent), 60), remoteInput.length());

        InterruptRequest request = InterruptRequest.builder()
                .message(remoteInput)
                .context(Map.of(
                        "agentName", remoteAgentName,
                        "_interrupt_kind", "a2a_delegate"))
                .build();
        ctx.getExtra().put(KEY_NORMALIZE_PENDING, Boolean.FALSE);
        return interrupt(request);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // afterToolCall：归一化 + 熔断后置 + history_info + 脱敏日志
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        if (!EdpaBusinessTools.TOOL_CALL_SUBAGENT.equals(inputs.getToolName())) {
            return;
        }

        boolean normalizePending = Boolean.TRUE.equals(ctx.getExtra().get(KEY_NORMALIZE_PENDING));
        ctx.getExtra().remove(KEY_NORMALIZE_PENDING);
        if (!normalizePending) {
            LOGGER.info("[SubagentDelegateRail] afterToolCall skipped (interrupt/degraded branch, no normalize)");
            return;
        }

        applyNormalizationAndTemplate(ctx, inputs);

        if (circuitBreaker != null) {
            Object rawResult = inputs.getToolResult();
            String status = "";
            if (rawResult instanceof Map<?, ?> m) {
                Object statusVal = m.get("status");
                status = statusVal != null ? String.valueOf(statusVal) : "";
            }
            if ("failed".equals(status)) {
                circuitBreaker.recordFailure();
            } else {
                circuitBreaker.recordSuccess();
            }
            LOGGER.info("[SubagentDelegateRail] circuit breaker recorded, status={}, state={}",
                    status, circuitBreaker.getState());
        }

        persistHistoryInfoIfPresent(ctx, inputs);

        LOGGER.info("[SubagentDelegateRail] call_subagent completed, normalized result applied");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 归一化脚本 + 话术模板
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyNormalizationAndTemplate(AgentCallbackContext ctx, ToolCallInputs inputs) {
        Map<String, Object> versatileArgs = normalizeArgs(inputs);
        Object rawToolResult = inputs.getToolResult();
        Map<String, Object> toolResult = toMap(rawToolResult);

        LOGGER.info("[SubagentDelegateRail] call_subagent params: intent='{}', desc='{}'",
                abbreviate(desensitize(asString(versatileArgs.get("query_intent"))), 60),
                abbreviate(desensitize(asString(versatileArgs.get("query_description"))), 80));

        String normalizeScript = resolveNormalizeScript(versatileArgs.get("query_response_analysis_scripts"));
        if (normalizeScript == null || normalizeScript.isBlank() || skillsDir == null || scripts == null) {
            inputs.setToolResult(toolResult);
            inputs.setToolMsg(buildToolMsg(inputs, toolResult));
            return;
        }

        NormalizeOutput output = invokeNormalizeScript(normalizeScript, toolResult, versatileArgs, ctx);
        if (output.uiNotice != null) {
            LOGGER.info("[SubagentDelegateRail] ui_notice: {}", desensitize(toJson(output.uiNotice)));
            ctx.getExtra().put(ScriptConstants.KEY_UI_NOTICE, output.uiNotice);
        } else if (output.status != null) {
            applyResponseTemplate(ctx, versatileArgs, output);
        } else {
            LOGGER.warn("[SubagentDelegateRail] no ui_notice or status in normalize output");
        }

        Map<String, Object> normalizedResult = output.data;
        if (output.uiNotice != null && normalizedResult != null) {
            normalizedResult.put("ui_notice", output.uiNotice);
        }
        inputs.setToolResult(normalizedResult);
        inputs.setToolMsg(buildToolMsg(inputs, normalizedResult));
        LOGGER.info("[SubagentDelegateRail] normalize applied, status={}, keys={}",
                output.status, normalizedResult != null ? normalizedResult.keySet() : "null");
    }

    private NormalizeOutput invokeNormalizeScript(String command, Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx) {
        NormalizeOutput fallback = new NormalizeOutput();
        fallback.status = null;
        fallback.data = toolResult;
        fallback.uiNotice = null;

        String scriptName = extractScriptName(command);
        if (scriptName.isBlank()) {
            LOGGER.info("[SubagentDelegateRail] normalize skipped, no .py in command={}", command);
            return fallback;
        }

        if (sysOp != null && sysOp.getMode() == OperationMode.SANDBOX && skillDeployPath != null) {
            return invokeNormalizeSandbox(command, toolResult, versatileArgs, ctx, fallback);
        }
        return invokeNormalizeLocal(toolResult, versatileArgs, ctx, fallback, scriptName);
    }

    private NormalizeOutput invokeNormalizeSandbox(String command, Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx, NormalizeOutput fallback) {
        Session previousSession = SessionContextHolder.getCurrentSession();
        if (ctx.getSession() != null) {
            SessionContextHolder.setCurrentSession(ctx.getSession());
        }
        try {
            Map<String, Object> skillInput = buildNormalizeSkillInput(toolResult, versatileArgs, ctx);
            String skillInputJson = OBJECT_MAPPER.writeValueAsString(skillInput);

            Map<String, String> env = new LinkedHashMap<>();
            env.put("SKILL_INPUT", skillInputJson);
            env.put("PYTHONIOENCODING", "utf-8");

            LOGGER.info("[SubagentDelegateRail] normalize via sandbox, command={}, cwd={}, governed={}",
                    command, skillDeployPath, decoratedClient != null);

            ExecuteCmdResult result;
            if (decoratedClient != null) {
                result = decoratedClient.shell().executeCmd(command, skillDeployPath,
                        ScriptConstants.SANDBOX_TIMEOUT_SECONDS, env, null);
            } else {
                result = sysOp.shell().executeCmd(command, skillDeployPath,
                        ScriptConstants.SANDBOX_TIMEOUT_SECONDS, env, null);
            }
            return adaptNormalizeResult(result, fallback);
        } catch (com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException e) {
            LOGGER.warn("[SubagentDelegateRail] normalize sandbox error, code={}, msg={}",
                    e.getErrorCode(), e.getMessage());
            return fallback;
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubagentDelegateRail] normalize sandbox exception, err={}", e.getMessage());
            return fallback;
        } finally {
            SessionContextHolder.setCurrentSession(previousSession);
        }
    }

    private NormalizeOutput invokeNormalizeLocal(Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx, NormalizeOutput fallback,
            String scriptName) {
        if (skillsDir == null) {
            return fallback;
        }
        Path scriptPath = skillsDir.resolve(scriptName).toAbsolutePath().normalize();
        if (!scriptPath.startsWith(skillsDir) || !Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            LOGGER.info("[SubagentDelegateRail] normalize skipped, script not found, path={}", scriptPath);
            return fallback;
        }
        try {
            Map<String, Object> skillInput = buildNormalizeSkillInput(toolResult, versatileArgs, ctx);
            String skillInputJson = OBJECT_MAPPER.writeValueAsString(skillInput);

            ProcessBuilder pb = new ProcessBuilder("python", scriptName);
            pb.directory(skillsDir.toFile());
            pb.environment().put("SKILL_INPUT", skillInputJson);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(ScriptConstants.SANDBOX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("[SubagentDelegateRail] normalize timeout, script={}", scriptPath);
                return fallback;
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.warn("[SubagentDelegateRail] normalize failed exit={}, script={}, stdout={}",
                        exitCode, scriptPath, abbreviate(desensitize(stdout)));
                return fallback;
            }
            return parseNormalizeStdout(stdout, fallback);
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("[SubagentDelegateRail] normalize exception, script={}, err={}",
                    scriptPath, e.getMessage());
            return fallback;
        }
    }

    private Map<String, Object> buildNormalizeSkillInput(Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx) {
        Map<String, Object> skillInput = new LinkedHashMap<>();
        skillInput.put("business_data", extractBusinessData(toolResult));
        skillInput.put("query_intent", versatileArgs.getOrDefault("query_intent", ""));
        skillInput.put("query_description", versatileArgs.getOrDefault("query_description", ""));
        Object noticeContext = versatileArgs.get("notice_context");
        if (noticeContext != null) {
            skillInput.put("notice_context", noticeContext);
        }
        Object historyInfo = toolDataChannel.getObject(ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName),
                HISTORY_INFO_KEY).orElse(null);
        skillInput.put("history_info", historyInfo instanceof Map<?, ?> m ? m.get("value") : List.of());
        return skillInput;
    }

    private Map<String, Object> extractBusinessData(Map<String, Object> toolResult) {
        Object content = toolResult.get("content");
        if (content instanceof String s && !s.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(s);
                if (node.isObject()) {
                    return OBJECT_MAPPER.convertValue(node, LINKED_MAP_TYPE);
                }
            } catch (JsonProcessingException e) {
                // content 不是 JSON，降级
            }
        }
        return toolResult;
    }

    private NormalizeOutput adaptNormalizeResult(ExecuteCmdResult result, NormalizeOutput fallback) {
        if (result == null || result.getData() == null) {
            return fallback;
        }
        int exitCode = result.getData().getExitCode() != null ? result.getData().getExitCode() : -1;
        if (exitCode != 0) {
            LOGGER.warn("[SubagentDelegateRail] normalize sandbox exit={}, stderr={}",
                    exitCode, result.getData().getStderr());
            return fallback;
        }
        String stdout = result.getData().getStdout() != null ? result.getData().getStdout() : "";
        if (stdout.isBlank()) {
            return fallback;
        }
        return parseNormalizeStdout(stdout, fallback);
    }

    private NormalizeOutput parseNormalizeStdout(String stdout, NormalizeOutput fallback) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(stdout);
            String status;
            Map<String, Object> normalized;
            if (root.isArray() && root.size() >= 2) {
                status = root.get(0).asText("");
                normalized = OBJECT_MAPPER.convertValue(root.get(1), LINKED_MAP_TYPE);
            } else if (root.isObject()) {
                normalized = OBJECT_MAPPER.convertValue(root, LINKED_MAP_TYPE);
                status = String.valueOf(normalized.getOrDefault("status", ""));
            } else {
                LOGGER.warn("[SubagentDelegateRail] normalize unexpected output, stdout={}",
                        abbreviate(desensitize(stdout)));
                return fallback;
            }
            NormalizeOutput output = new NormalizeOutput();
            output.status = status;
            output.data = normalized;
            Object uiNoticeRaw = normalized.remove("ui_notice");
            if (uiNoticeRaw instanceof Map<?, ?> um) {
                output.uiNotice = toStringKeyMap(um);
            }
            LOGGER.info("[SubagentDelegateRail] normalize done, status={}, uiNoticeKey={}, keys={}",
                    status, output.uiNotice != null ? output.uiNotice.get("key") : "null", normalized.keySet());
            return output;
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubagentDelegateRail] normalize parse exception, err={}", e.getMessage());
            return fallback;
        }
    }

    private void applyResponseTemplate(AgentCallbackContext ctx, Map<String, Object> versatileArgs,
            NormalizeOutput output) {
        String templateKey = resolveTemplateKey(versatileArgs.get("response_template_keys"), output.status)
                .orElse(null);
        if (templateKey == null) {
            return;
        }
        String text = scripts.getTemplate(templateKey).orElse(null);
        if (text == null || text.isBlank()) {
            return;
        }
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, text);
        ctx.getExtra().put(ScriptConstants.KEY_LAST_SCRIPT, templateKey);
        LOGGER.info("[SubagentDelegateRail] response_template injected, key={}, status={}",
                templateKey, output.status);
    }

    private Optional<String> resolveTemplateKey(Object keysObj, String status) {
        if (keysObj == null || status == null || status.isBlank()) {
            return Optional.empty();
        }
        List<String> keys;
        try {
            if (keysObj instanceof String s) {
                keys = OBJECT_MAPPER.readValue(s, STRING_LIST_TYPE);
            } else if (keysObj instanceof List<?> l) {
                keys = l.stream().map(String::valueOf).toList();
            } else {
                return Optional.empty();
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubagentDelegateRail] response_template_keys parse failed, err={}", e.getMessage());
            return Optional.empty();
        }
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        int idx = "success".equals(status) ? 0 : ("failure".equals(status) ? 1 : -1);
        if (idx < 0 || idx >= keys.size()) {
            return Optional.empty();
        }
        return Optional.of(keys.get(idx));
    }

    private String resolveNormalizeScript(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return "";
    }

    private String extractScriptName(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        for (String token : command.split("\\s+")) {
            if (token.endsWith(".py")) {
                return token;
            }
        }
        return "";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // pre-delegate guard
    // ═══════════════════════════════════════════════════════════════════════════

    private Optional<GuardDecision> applyPreDelegateGuard(AgentCallbackContext ctx, Map<String, Object> args) {
        String command = asString(args.get("query_response_analysis_scripts"));
        Map<String, Object> guard = loadPreDelegateGuard(command);
        if (guard == null || guard.isEmpty()) {
            return Optional.empty();
        }
        Object rulesObj = guard.get("rules");
        if (!(rulesObj instanceof List<?> rules) || rules.isEmpty()) {
            return Optional.empty();
        }
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        for (Object ruleObj : rules) {
            if (!(ruleObj instanceof Map<?, ?> rule)) {
                continue;
            }
            Map<String, Object> ruleMap = toStringKeyMap(rule);
            Map<String, Object> match = toStringKeyMap(ruleMap.get("match"));
            if (!match.isEmpty() && !matchesArgs(args, match)) {
                continue;
            }
            String ruleId = asString(ruleMap.getOrDefault("id", "default"));
            String stateKey = GUARD_STATE_KEY_PREFIX + command + ":" + ruleId;
            int count = incrementGuardCount(channelKey, stateKey);
            int maxCalls = asInt(ruleMap.get("max_calls"), 0);
            LOGGER.info("[SubagentDelegateRail] pre-delegate guard matched rule={}, count={}, limit={}, match={}",
                    ruleId, count, maxCalls, match);
            if (maxCalls > 0 && count > maxCalls) {
                String message = resolveGuardMessage(ruleMap);
                return Optional.of(new GuardDecision(true, ruleId, count, maxCalls, message));
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> loadPreDelegateGuard(String command) {
        String script = extractScriptName(command);
        if (script.isBlank() || skillsDir == null) {
            return Map.of();
        }
        Path scriptPath = skillsDir.resolve(script).toAbsolutePath().normalize();
        if (!scriptPath.startsWith(skillsDir) || !Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            LOGGER.info("[SubagentDelegateRail] pre-delegate guard skipped, script not found, path={}", scriptPath);
            return Map.of();
        }
        try {
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);
            String literal = extractAssignLiteral(source, "PRE_DELEGATE_GUARD").orElse(null);
            if (literal == null) {
                LOGGER.info("[SubagentDelegateRail] pre-delegate guard skipped, PRE_DELEGATE_GUARD not found, path={}",
                        scriptPath);
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(literal, Map.class);
            int ruleCount = parsed.get("rules") instanceof List<?> l ? l.size() : 0;
            LOGGER.info("[SubagentDelegateRail] pre-delegate guard loaded, path={}, rules={}", scriptPath, ruleCount);
            return parsed;
        } catch (IOException e) {
            LOGGER.warn("[SubagentDelegateRail] pre-delegate guard parse failed, path={}, err={}",
                    scriptPath, e.getMessage());
            return Map.of();
        }
    }

    private Optional<String> extractAssignLiteral(String source, String name) {
        int assignIdx = source.indexOf(name + " =");
        if (assignIdx < 0) {
            assignIdx = source.indexOf(name + "=");
        }
        if (assignIdx < 0) {
            return Optional.empty();
        }
        int braceStart = source.indexOf('{', assignIdx + name.length());
        if (braceStart < 0) {
            return Optional.empty();
        }
        int depth = 0;
        boolean inString = false;
        char quoteChar = 0;
        for (int i = braceStart; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                    continue;
                }
                if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inString = true;
                quoteChar = ch;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(source.substring(braceStart, i + 1));
                }
            } else {
                continue;
            }
        }
        return Optional.empty();
    }

    private boolean matchesArgs(Map<String, Object> args, Map<String, Object> match) {
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            Object actual = args.get(entry.getKey());
            Object expected = entry.getValue();
            if (actual == null ? expected != null : !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private int incrementGuardCount(ToolDataKey channelKey, String stateKey) {
        Object current = toolDataChannel.getObject(channelKey, stateKey).orElse(null);
        int count = (current instanceof Number n ? n.intValue() : 0) + 1;
        toolDataChannel.store(channelKey, stateKey, count);
        return count;
    }

    private String resolveGuardMessage(Map<String, Object> ruleMap) {
        String templateKey = asString(ruleMap.get("response_template_key"));
        String text = "";
        if (scripts != null && templateKey != null && !templateKey.isBlank()) {
            String resolved = scripts.getTemplate(templateKey).orElse(null);
            if (resolved != null && !resolved.isBlank()) {
                text = resolved;
            }
        }
        if (text.isEmpty()) {
            text = asString(ruleMap.get("fallback_message"));
        }
        return text;
    }

    private record GuardDecision(boolean blocked, String ruleId, int count, int maxCalls, String message) {
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // history_info 持久化 + 出口剔除
    // ═══════════════════════════════════════════════════════════════════════════

    private void persistHistoryInfoIfPresent(AgentCallbackContext ctx, ToolCallInputs inputs) {
        Object rawResult = inputs.getToolResult();
        if (!(rawResult instanceof Map<?, ?> rawMap)) {
            LOGGER.info("[SubagentDelegateRail] persistence check history_info=not in result");
            return;
        }
        Map<String, Object> result = toStringKeyMap(rawMap);
        if (!result.containsKey(HISTORY_INFO_KEY)) {
            LOGGER.info("[SubagentDelegateRail] persistence check history_info=not in result");
            return;
        }
        Object historyInfo = result.get(HISTORY_INFO_KEY);
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        toolDataChannel.store(channelKey, HISTORY_INFO_KEY,
                Map.of("value", historyInfo != null ? historyInfo : List.of()));
        LOGGER.info("[SubagentDelegateRail] persistence check history_info=persisted:{}",
                abbreviate(desensitize(String.valueOf(historyInfo))));

        result.remove(HISTORY_INFO_KEY);
        inputs.setToolResult(result);
        inputs.setToolMsg(buildToolMsg(inputs, result));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 脱敏
    // ═══════════════════════════════════════════════════════════════════════════

    private static String desensitize(String json) {
        if (json == null || "null".equals(json)) {
            return json;
        }
        String result = json.replaceAll(
                "\"(bankCardNumber|payerCardNumber|payeeCardNumber|cardNum|cardNumber)\""
                        + "\\s*:\\s*\"(\\d{4})\\d+(\\d{4})\"",
                "\"$1\":\"$2****$3\"");
        result = BANK_CARD_NUMBER_PATTERN.matcher(result)
                .replaceAll(m -> m.group(1).substring(0, 4) + "****"
                        + m.group(1).substring(m.group(1).length() - 4));
        result = result.replaceAll(
                "\"(wap_userName|wap_realName|userName|realName|customerName)\"\\s*:\\s*\"([^\"]{1})[^\"]+\"",
                "\"$1\":\"$2***\"");
        result = result.replaceAll("\"(wap_sessionId|sessionId)\"\\s*:\\s*\"([^\"]{8})[^\"]+\"",
                "\"$1\":\"$2***\"");
        result = result.replaceAll("\"(wapbCookieList|cookieList|cookies)\"\\s*:\\s*\"[^\"]+\"", "\"$1\":\"***\"");
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════════════

    private void injectCachedQueryDescription(Map<String, Object> args, AgentCallbackContext ctx) {
        String query = String.valueOf(args.getOrDefault("query_description", ""));
        boolean queryFromArgs = !query.isBlank();
        if (queryFromArgs) {
            LOGGER.debug("[SubagentDelegateRail] query_description: fromArgs=true, skip cache injection, len={}",
                    query.length());
            return;
        }
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        String cachedQuery = readCachedQuery(channelKey);
        if (!cachedQuery.isBlank()) {
            args.put("query_description", cachedQuery);
            LOGGER.info("[SubagentDelegateRail] query_description: fromArgs=false, fallbackToCache=true, finalLen={}",
                    cachedQuery.length());
        } else {
            LOGGER.warn("[SubagentDelegateRail] query_description: fromArgs=false, "
                    + "fallbackToCache=false, cache empty");
        }
    }

    private String readCachedQuery(ToolDataKey channelKey) {
        Object cached = toolDataChannel.getObject(channelKey, McpInterruptRail.VERSATILE_QUERY_KEY).orElse(null);
        if (cached instanceof String text && !text.isBlank()) {
            return text;
        }
        if (cached instanceof Map<?, ?> map) {
            Object value = map.get("query_description");
            if (value == null) {
                value = map.get("query");
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }

        Object mcpProductsData = toolDataChannel.getObject(channelKey,
                McpInterruptRail.DEFAULT_MCP_PRODUCTS_KEY).orElse(null);
        if (mcpProductsData instanceof Map<?, ?> productsMap && !productsMap.isEmpty()) {
            String dataStr = String.valueOf(productsMap);
            if (!dataStr.isBlank() && !"{}".equals(dataStr)) {
                LOGGER.warn("[SubagentDelegateRail] fallback query from mcp_products_data, length={}",
                        dataStr.length());
                return dataStr;
            }
        }

        LOGGER.warn("[SubagentDelegateRail] readCachedQuery: all caches empty, returning empty query");
        return "";
    }

    private static String extractRemoteInput(ToolCall toolCall, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        Map<String, Object> flat = new LinkedHashMap<>(args);
        Object queryValue = flat.getOrDefault("query_description", flat.get("query"));
        flat.put("query", String.valueOf(queryValue != null ? queryValue : ""));
        Object intentValue = flat.get("query_intent");
        if (intentValue == null || String.valueOf(intentValue).isBlank()) {
            intentValue = flat.get("agent_name");
        }
        flat.put("intent", String.valueOf(intentValue != null ? intentValue : ""));
        try {
            return OBJECT_MAPPER.writeValueAsString(flat);
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubagentDelegateRail] failed to serialize flat content, falling back to empty json", e);
            return "{}";
        }
    }

    private static Map<String, Object> parseToolArgs(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Map.of();
        }
        String arguments = toolCall.getArguments();
        try {
            return OBJECT_MAPPER.readValue(arguments, MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubagentDelegateRail] failed to parse tool arguments, raw={}", arguments, e);
            return Map.of();
        }
    }

    private Map<String, Object> normalizeArgs(ToolCallInputs inputs) {
        Map<String, Object> args = toMap(inputs.getToolArgs());
        if (args.isEmpty() && inputs.getToolCall() != null) {
            args = parseToolArgs(inputs.getToolCall());
        }
        return args;
    }

    private static Map<String, Object> toMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (obj instanceof String s && !s.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(s);
                if (node != null && node.isObject()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    node.fields().forEachRemaining(e -> result.put(e.getKey(),
                            OBJECT_MAPPER.convertValue(e.getValue(), Object.class)));
                    return result;
                }
            } catch (JsonProcessingException e) {
                // 降级返回空 Map
            }
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> toStringKeyMap(Object source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
        }
        return result;
    }

    private ToolMessage buildToolMsg(ToolCallInputs inputs, Object content) {
        String toolCallId = inputs.getToolCall() != null && inputs.getToolCall().getId() != null
                ? inputs.getToolCall().getId() : EdpaBusinessTools.TOOL_CALL_SUBAGENT;
        return ToolMessage.builder().content(toJson(content)).toolCallId(toolCallId).build();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> failedResult(String error) {
        return Map.of("source", "subagent", "status", "failed", "error",
                error != null ? error : "unknown");
    }

    private String resolveConversationId(AgentCallbackContext ctx) {
        return ctx.getSession() != null && ctx.getSession().getSessionId() != null
                ? ctx.getSession().getSessionId() : "call-subagent-spike";
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落入默认值
            }
        }
        return defaultValue;
    }

    private static String abbreviate(String value) {
        return abbreviate(value, 2000);
    }

    private static String abbreviate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...(truncated)";
    }

    /** 归一化脚本输出封装。 */
    private static class NormalizeOutput {
        String status;
        Map<String, Object> data;
        Map<String, Object> uiNotice;
    }
}
