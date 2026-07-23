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
import com.huawei.ascend.edp.config.SysScriptsConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Versatile Agent 委托调用 Rail。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>在 call_versatile 工具调用前拦截并直连 Versatile REST 或 adapter A2A。</li>
 *     <li>解析 adapter SSE 响应，分离 USER 透传节点与 LLM 终态内容。</li>
 *     <li>adapter 请求用户输入时抛出 {@link ToolInterruptException}，由 runtime 续传。</li>
 *     <li>续传恢复时从 {@link ToolInterruptionState#RESUME_USER_INPUT_KEY} 回填工具结果。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #beforeToolCall(AgentCallbackContext)}：工具调用前回调入口。</li>
 *     <li>{@link #invokeWithInputs(Map, String)}：供 handler 层 Versatile 菜单续传直接调用。</li>
 *     <li>{@link VersatilePassthroughBuffer}：与 {@link com.huawei.ascend.edp.handler.EdpaRuntimeHandler}
 *         共享的会话级 USER 节点缓冲。</li>
 * </ul>
 *
 * @since 2024-01-01
 *
 */

public class VersatileInterruptRail extends AgentRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersatileInterruptRail.class);

    /**
     * pre-delegate guard 计数器在 ToolDataChannel 中的 key 前缀。
     * 对齐 Python versatile_interrupt_rail.py 第 434 行 {@code state_key = f"_pre_delegate_guard:{command}:{rule_id}"}，
     * 用 {@code command:ruleId} 作为唯一标识，避免不同 skill 的同名规则互相干扰。
     *
     */

    static final String GUARD_STATE_KEY_PREFIX = "_pre_delegate_guard:";

    /**
     * history_info 字段名。与 {@link McpInterruptRail#HISTORY_INFO_KEY} 同名，
     * 保证 call_versatile 写入的 history_info 能被下一次 call_mcp 的 buildSkillInput 读到，
     * 形成跨工具的会话级持久化闭环（对齐 Python {@code session.state["history_info"]}）。
     *
     */

    static final String HISTORY_INFO_KEY = "history_info";

    /**
     * 中国银联卡号数字模式：以62开头的16-19位连续数字，覆盖所有银联BIN（工行6222/建行6217/农行6228/招行6225等）。
     */
    private static final Pattern BANK_CARD_NUMBER_PATTERN = Pattern.compile("(62\\d{14,17})");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * EDP 专有配置，当前预留给 VA 委托策略和目标 Agent 配置使用。
     *
     */

    private final EdpConfig edpConfig;

    private final EdpaSpringBootConfig.VersatileConfig versatileConfig;
    private final ToolDataChannel toolDataChannel;

    /**
     * 与 EdpaRuntimeHandler 共享，存放 adapter 返回的完整 Versatile message JSON。
     */
    private final VersatilePassthroughBuffer passthroughBuffer;
    private final HttpClient httpClient;

    /**
     * skills 目录，用于 pre-delegate guard 静态解析脚本中的 {@code PRE_DELEGATE_GUARD} 配置。
     * 为 null 时 guard 直接跳过（不影响主流程）。
     *
     */

    private final Path skillsDir;

    /**
     * 话术配置，用于 pre-delegate guard 超限时解析 {@code response_template_key} 兜底话术。
     * 为 null 时回落到规则的 {@code fallback_message}。
     *
     */

    private final SysScriptsConfig scripts;

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

    /**
     * 构造 VA 委托 Rail。
     *
     * @param edpConfig EDP 专有配置
     *
     * @return result
     *
     */

    public VersatileInterruptRail(EdpConfig edpConfig) {
        this(edpConfig, null, new ToolDataChannel(), new VersatilePassthroughBuffer(), null, null, "EDPAgent", null,
                null);
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig) {
        this(edpConfig, versatileConfig, new ToolDataChannel(), new VersatilePassthroughBuffer(), null, null,
                "EDPAgent", null, null);
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel) {
        this(edpConfig, versatileConfig, toolDataChannel, new VersatilePassthroughBuffer(), null, null, "EDPAgent",
                null, null);
    }

    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, null, null, "EDPAgent", null, null);
    }

    // 5参数版(含ToolDataChannel + skillsDir)
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer, Path skillsDir) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, skillsDir, null, "EDPAgent", null, null);
    }

    // 6参数版(含skillsDir + scripts)
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer, Path skillsDir,
            SysScriptsConfig scripts) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, skillsDir, scripts, "EDPAgent", null,
                null);
    }

    // 7参数版(含skillsDir + scripts + agentName) → 转发到9参数版（含 sysOp + skillDeployPath）
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer, Path skillsDir,
            SysScriptsConfig scripts, String agentName) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, skillsDir, scripts, agentName, null, null);
    }

    // 9参数版（含 sysOp + skillDeployPath）
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer, Path skillsDir,
            SysScriptsConfig scripts, String agentName, SysOperation sysOp, String skillDeployPath) {
        this(edpConfig, versatileConfig, toolDataChannel, passthroughBuffer, skillsDir, scripts, agentName, sysOp,
                skillDeployPath, null);
    }

    // 10参数版（全参构造，含 sysOp + skillDeployPath + decoratedClient）
    public VersatileInterruptRail(EdpConfig edpConfig, EdpaSpringBootConfig.VersatileConfig versatileConfig,
            ToolDataChannel toolDataChannel, VersatilePassthroughBuffer passthroughBuffer, Path skillsDir,
            SysScriptsConfig scripts, String agentName, SysOperation sysOp, String skillDeployPath,
            SandboxClient decoratedClient) {
        this.edpConfig = edpConfig;
        this.versatileConfig = versatileConfig;
        this.toolDataChannel = toolDataChannel != null ? toolDataChannel : new ToolDataChannel();
        this.passthroughBuffer = passthroughBuffer != null ? passthroughBuffer : new VersatilePassthroughBuffer();
        this.skillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize() : null;
        this.scripts = scripts;
        this.agentName = agentName;
        this.sysOp = sysOp;
        this.skillDeployPath = skillDeployPath;
        this.decoratedClient = decoratedClient;
        Duration timeout = versatileConfig != null
                ? parseTimeout(versatileConfig.getTimeout())
                : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();

        // VA 与 MCP、ask_user 同属工具调用增强类 Rail，使用同一优先级。
        setPriority(85);
    }

    /**
     * 工具调用前回调。
     *
     * <p>执行顺序（对齐 Python {@code resolve_interrupt}）：</p>
     * <ol>
     *     <li>续传路径：用户已提交菜单确认等输入，直接回填工具结果。</li>
     *     <li>pre-delegate guard：从脚本静态读取 {@code PRE_DELEGATE_GUARD}，超限则终止。</li>
     *     <li>委托 adapter 执行业务流程。</li>
     *     <li>归一化脚本执行 + 话术索引（对齐 Python versatile_interrupt_rail.py L190-260）。</li>
     * </ol>
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用信息
     *
     */

    @Override
    /**
     * Before tool call.
     *
     * @param ctx the ctx value
     */
    public void beforeToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才需要识别 VA 委托工具。
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // 关键判断：只拦截 call_versatile，其他工具直接放行。
        if ("call_versatile".equals(toolName)) {
            LOGGER.info("[VersatileInterruptRail] intercept call_versatile: toolCallId={}", toolCallId(inputs));
            String toolCallId = toolCallId(inputs);

            // 续传路径：用户已提交菜单确认等输入，直接回填工具结果，不再重复调 adapter。
            Object resumeInput = resolveResumeInput(ctx, toolCallId);
            if (resumeInput != null) {
                LOGGER.info("[VersatileInterruptRail] cascade resume: toolCallId={}, hasResumeInput=true", toolCallId);
                ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
                Object toolResult = normalizeResumeToolResult(resumeInput);
                inputs.setToolResult(toolResult);
                inputs.setToolMsg(ToolMessage.builder().content(toJson(toolResult)).toolCallId(toolCallId).build());
                return;
            }
            LOGGER.info("[VersatileInterruptRail] intercepting call_versatile, direct call to versatile service");

            // pre-delegate guard：在真正委托 adapter 之前检查 skill 声明的前置规则。
            // 超限时直接终止当前 ReAct 流程，避免 adapter 继续执行真实业务（如转账）。
            // 对齐 Python versatile_interrupt_rail.py 第 128-130 行。
            GuardDecision guardDecision = applyPreDelegateGuard(ctx, normalizeArgs(inputs)).orElse(null);
            if (guardDecision != null && guardDecision.blocked()) {
                LOGGER.info("[VersatileInterruptRail] guard blocked: rule={}, count={}, limit={}",
                        guardDecision.ruleId(), guardDecision.count(), guardDecision.maxCalls());
                blockCallVersatileByGuard(ctx, inputs, toolCallId, guardDecision);
                return;
            }
            LOGGER.debug("[VersatileInterruptRail] guard passed or skipped for call_versatile");

            ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);

            Map<String, Object> toolResult = callVersatile(inputs, ctx);
            if (isInputRequired(toolResult)) {
                // adapter 进入 INPUT_REQUIRED：先刷透传节点，再中断等待用户确认。
                LOGGER.info("[VersatileInterruptRail] cascade inputRequired: toolCallId={}, toolResultKeys={}",
                        toolCallId, toolResult.keySet());
                throw inputRequiredInterrupt(ctx, inputs, toolResult);
            }

            // ── 归一化脚本 + 话术索引（对齐 Python versatile_interrupt_rail.py L190-260）──
            applyNormalizationAndTemplate(inputs, toolResult, ctx);
        }
    }

    private void applyNormalizationAndTemplate(ToolCallInputs inputs, Map<String, Object> toolResult,
            AgentCallbackContext ctx) {
        Map<String, Object> versatileArgs = normalizeArgs(inputs);
        LOGGER.info("[VersatileInterruptRail] call_versatile params: intent='{}', desc='{}'",
                abbreviate(String.valueOf(versatileArgs.getOrDefault("query_intent", "")), 60),
                abbreviate(String.valueOf(versatileArgs.getOrDefault("query_description", "")), 80));
        String normalizeScript = resolveNormalizeScript(versatileArgs.get("query_response_analysis_scripts"));
        if (normalizeScript != null && !normalizeScript.isBlank() && skillsDir != null && scripts != null) {
            NormalizeOutput output = invokeNormalizeScript(normalizeScript, toolResult, versatileArgs, ctx);
            if (output.uiNotice != null) {
                LOGGER.info("[VersatileInterruptRail] ui_notice: templateKey='{}', deliveryMode=direct",
                        output.uiNotice);
                ctx.getExtra().put(ScriptConstants.KEY_UI_NOTICE, output.uiNotice);
            } else if (output.status != null) {
                applyResponseTemplate(ctx, versatileArgs, output);
            } else {
                LOGGER.warn("no ui_notice or status in normalize output");
            }
            Map<String, Object> normalizedResult = output.data;
            inputs.setToolResult(normalizedResult);
            inputs.setToolMsg(ToolMessage.builder().content(toJson(normalizedResult))
                    .toolCallId(inputs.getToolCall() != null ? inputs.getToolCall().getId() : "call_versatile")
                    .build());
            if (output.uiNotice != null && normalizedResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> trMap = (Map<String, Object>) normalizedResult;
                trMap.put("ui_notice", output.uiNotice);
                inputs.setToolResult(trMap);
            }
        } else {
            inputs.setToolResult(toolResult);
            inputs.setToolMsg(ToolMessage.builder().content(toJson(toolResult))
                    .toolCallId(inputs.getToolCall() != null ? inputs.getToolCall().getId() : "call_versatile")
                    .build());
        }
    }

    /**
     * 根据归一化脚本的 status 索引话术模板并注入到 ctx.extra（G.MET.01：从 applyNormalizationAndTemplate 提取以降低嵌套深度）。
     *
     * @param ctx the ctx value
     * @param versatileArgs the versatileArgs value
     * @param output the output value
     */
    private void applyResponseTemplate(AgentCallbackContext ctx, Map<String, Object> versatileArgs,
            NormalizeOutput output) {
        String templateKey = resolveTemplateKey(versatileArgs.get("response_template_keys"),
                output.status).orElse(null);
        if (templateKey == null) {
            return;
        }
        String text = scripts.getTemplate(templateKey).orElse(null);
        if (text == null || text.isBlank()) {
            return;
        }
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, text);
        ctx.getExtra().put(ScriptConstants.KEY_LAST_SCRIPT, templateKey);
        LOGGER.info("[VersatileInterruptRail] response_template injected, key={}, status={}",
                templateKey, output.status);
    }

    /**
     * pre-delegate guard 超限时的统一收尾：写话术、强制结束、回填失败 toolResult。
     *
     * <p>必须同时设置 toolResult 与 toolMsg，否则 OpenJiuwen 会因 tool_call 无对应 tool_response
     * 而在 forceFinish 后的 LLM 调用中 HTTP 400（沿用 {@link CancelRail} 的成熟模式）。
     * 对齐 Python {@code _apply_pre_delegate_guard} 第 452-468 行：</p>
     * <ul>
     *     <li>response_template 写入 extra（北向话术通道，由 EdpaEventRail 出口发射）。</li>
     *     <li>{@code requestForceFinish} 终止 ReAct 循环。</li>
     *     <li>{@code reject} 跳过本次工具调用 → 此处用 KEY_SKIP_TOOL=true 等价。</li>
     * </ul>
     *
     * <p>前置规则（pre-delegate guard）超限拦截：当同一 rule 的调用次数超过 limit 时，
     * 注入 response_template 并 request_force_finish。
     * 对齐 Python versatile_interrupt_rail.py L439-473: guard matched → exceeded → force_finish。</p>
     *
     * @param ctx the ctx value
     * @param inputs the inputs value
     * @param toolCallId the toolCallId value
     * @param decision the decision value
     */

    private void blockCallVersatileByGuard(AgentCallbackContext ctx, ToolCallInputs inputs, String toolCallId,
            GuardDecision decision) {
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE, decision.message());
        ctx.requestForceFinish(Map.of("result_type", "interrupt", "state", List.of(), "interrupt_ids", List.of()));
        Map<String, Object> blockedResult = new LinkedHashMap<>();
        blockedResult.put("status", "failed");
        blockedResult.put("message", decision.message());
        inputs.setToolResult(blockedResult);
        inputs.setToolMsg(ToolMessage.builder().content(toJson(blockedResult)).toolCallId(toolCallId).build());
        ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
        LOGGER.warn("[VersatileInterruptRail] guard blocked: rule={}, count={}, limit={}, message={}",
                decision.ruleId(), decision.count(), decision.maxCalls(), decision.message());
    }

    private Map<String, Object> callVersatile(ToolCallInputs inputs, AgentCallbackContext ctx) {
        if (versatileConfig == null || versatileConfig.getUrl() == null || versatileConfig.getUrl().isBlank()) {
            return failedResult("versatile config is missing");
        }
        try {
            Map<String, Object> args = normalizeArgs(inputs);
            String conversationId = ctx.getSession() != null && ctx.getSession().getSessionId() != null
                    ? ctx.getSession().getSessionId()
                    : "call-versatile-spike";
            Map<String, Object> versatileInputs = buildInputs(args, ctx);
            return invokeWithInputs(versatileInputs, conversationId);
        } catch (IllegalStateException e) {
            LOGGER.warn("VersatileInterruptRail: direct call failed: {}", e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    private String toolCallId(ToolCallInputs inputs) {
        return inputs.getToolCall() != null && inputs.getToolCall().getId() != null
                && !inputs.getToolCall().getId().isBlank() ? inputs.getToolCall().getId() : "call_versatile";
    }

    private Object resolveResumeInput(AgentCallbackContext ctx, String toolCallId) {
        // runtime 在中断恢复时把用户输入写入 extra；优先按 toolCallId 精确匹配。
        Object rawInput = ctx.getExtra().get(ToolInterruptionState.RESUME_USER_INPUT_KEY);
        if (rawInput instanceof InteractiveInput interactiveInput) {
            Map<String, Object> userInputs = interactiveInput.getUserInputs();
            if (toolCallId != null && !toolCallId.isBlank() && userInputs.containsKey(toolCallId)) {
                return userInputs.get(toolCallId);
            }
            return interactiveInput.getRawInputs();
        }
        if (rawInput instanceof Map<?, ?> map && toolCallId != null && !toolCallId.isBlank()
                && map.containsKey(toolCallId)) {
            return map.get(toolCallId);
        }
        return rawInput;
    }

    private Object normalizeResumeToolResult(Object resumeInput) {
        if (resumeInput instanceof String text && !text.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (JsonProcessingException e) {
                return Map.of("source", "versatile", "status", "completed", "content", text);
            }
        }
        return resumeInput;
    }

    /**
     * Invoke with inputs.
     *
     * @param versatileInputs the versatileInputs value
     * @param conversationId the conversationId value
     * @return the result
     */
    public Map<String, Object> invokeWithInputs(Map<String, Object> versatileInputs, String conversationId) {
        if (versatileConfig == null) {
            return failedResult("versatile config is missing");
        }
        boolean hasAdapterA2a = versatileConfig.getAdapterA2aUrl() != null
                && !versatileConfig.getAdapterA2aUrl().isBlank();
        boolean hasDirectUrl = versatileConfig.getUrl() != null && !versatileConfig.getUrl().isBlank();
        if (!hasAdapterA2a && !hasDirectUrl) {
            return failedResult("versatile config is missing");
        }
        try {
            Map<String, Object> result = hasAdapterA2a
                    ? callVersatileAdapterA2a(versatileInputs, conversationId)
                    : callVersatileDirect(versatileInputs, conversationId);
            storePassthroughNodes(conversationId, result);
            return result;
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("VersatileInterruptRail: direct call failed: {}", e.getMessage());
            return failedResult(e.getMessage());
        }
    }

    private Map<String, Object> callVersatileDirect(Map<String, Object> versatileInputs, String conversationId)
            throws JsonProcessingException, IOException, InterruptedException {
        String url = resolveUrl(conversationId);
        Map<String, Object> body = Map.of("inputs", versatileInputs, "stream", true);
        String bodyJson = OBJECT_MAPPER.writeValueAsString(body);
        LOGGER.info("[VersatileInterruptRail] request body {}", desensitizeSensitiveFields(abbreviate(bodyJson, 500)));

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(parseTimeout(versatileConfig.getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        Map<String, String> headers = versatileConfig.getHeaders() != null ? versatileConfig.getHeaders() : Map.of();
        headers.forEach(builder::header);
        if (!hasHeader(headers, "content-type")) {
            builder.header("Content-Type", "application/json");
        }

        LOGGER.info("VersatileInterruptRail: POST {}", url);
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        LOGGER.info("[VersatileInterruptRail] response status={} body={}", response.statusCode(),
                desensitizeSensitiveFields(abbreviate(response.body())));
        if (response.statusCode() >= 400) {
            return failedResult("HTTP " + response.statusCode() + ": " + response.body());
        }
        String content = normalizeContent(response.body());
        LOGGER.info("VersatileInterruptRail: normalized content {}", content);
        return Map.of("source", "versatile", "status", "completed", "content", content);
    }

    private Map<String, Object> normalizeArgs(ToolCallInputs inputs) {
        Map<String, Object> args = normalizeArgsObject(inputs.getToolArgs());
        if (args.isEmpty() && inputs.getToolCall() != null) {
            args = normalizeArgsObject(inputs.getToolCall().getArguments());
        }
        return args;
    }

    /**
     * 通过 adapter-versatile-agent-java 的 A2A SendStreamingMessage 发起 SSE 调用。
     *
     * @param versatileInputs the versatileInputs value
     * @param conversationId the conversationId value
     * @return the result
     * @throws JsonProcessingException the json processing exception
     * @throws IOException the io exception
     * @throws InterruptedException the interrupted exception
     */
    private Map<String, Object> callVersatileAdapterA2a(Map<String, Object> versatileInputs, String conversationId)
            throws JsonProcessingException, IOException, InterruptedException {
        String adapterUrl = versatileConfig.getAdapterA2aUrl();

        // 扁平化字段到顶层，确保 query 和 intent 同时以两个键名存在，
        // 以便 VersatileRequestExtractor.extractSemanticInput() 正确提取 query 和 intent。
        // 嵌套 {"inputs:{...}} 格式会使 VersatileRequestExtractor 只看到 "inputs" 键，无法路由。
        Map<String, Object> flatContent = new LinkedHashMap<>(versatileInputs);
        Object queryValue = flatContent.getOrDefault("query_description", flatContent.get("query"));
        flatContent.put("query", String.valueOf(queryValue != null ? queryValue : ""));
        Object intentValue = flatContent.getOrDefault("query_intent", flatContent.get("intent"));
        flatContent.put("intent", String.valueOf(intentValue != null ? intentValue : ""));
        String messageText = OBJECT_MAPPER.writeValueAsString(flatContent);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", conversationId);
        message.put("parts", List.of(Map.of("text", messageText)));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", agentName);
        metadata.put("agentId", agentName);
        metadata.put("versatile", Map.of("inputs", versatileInputs));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("metadata", metadata);
        params.put("message", message);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "SendStreamingMessage");
        requestBody.put("id", "call-versatile-" + UUID.randomUUID());
        requestBody.put("params", params);

        String bodyJson = OBJECT_MAPPER.writeValueAsString(requestBody);
        LOGGER.info("[VersatileInterruptRail] POST adapter A2A {}", adapterUrl);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(adapterUrl))
                .timeout(parseTimeout(versatileConfig.getTimeout())).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        LOGGER.info("[VersatileInterruptRail] adapter A2A status={} body={}", response.statusCode(),
                desensitizeSensitiveFields(abbreviate(response.body())));
        if (response.statusCode() >= 400) {
            return failedResult("adapter A2A HTTP " + response.statusCode() + ": " + response.body());
        }
        return normalizeA2aAdapterResponse(response.body());
    }

    /**
     * 解析 adapter A2A SSE 响应体。
     * artifactUpdate 中的 text 为 USER 透传节点；statusUpdate 中的 text 为 LLM 终态内容。
     *
     * @param body the body value
     * @return the result
     * @throws JsonProcessingException the json processing exception
     */
    private Map<String, Object> normalizeA2aAdapterResponse(String body) throws JsonProcessingException {
        List<String> passthroughNodes = new ArrayList<>();
        String completedContent = "";
        String state = "";
        for (String line : body != null ? body.split("\\R") : new String[0]) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode result = root.path("result");
            state = extractA2aState(result, state);
            String artifactText = extractA2aArtifactText(result);
            if (!artifactText.isBlank()) {
                passthroughNodes.add(artifactText);
            }
            String terminalText = extractA2aStatusText(result);
            if (!terminalText.isBlank()) {
                completedContent = terminalText;
            }
        }

        // ── 兜底回填：当 completedContent 为空时，从 passthroughNodes 提取 answer 内容 ──
        // VersatileResponseExtractor 产生的 answer envelope {type:"answer", output:...}
        // 在 A2A SSE 中映射为 artifactUpdate（而非 statusUpdate），因此 extractA2aStatusText
        // 无法提取。此处从 passthroughNodes 回查 answer 节点并提取 output 字段，
        // 确保 LLM 能看到 Versatile 的结果数据（0705版已验证逻辑，0707版合并时缺失）。
        if (completedContent.isBlank() && !passthroughNodes.isEmpty()) {
            completedContent = extractContentFromPassthroughNodes(passthroughNodes);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("source", "versatile");
        normalized.put("status", state.equalsIgnoreCase("TASK_STATE_INPUT_REQUIRED") ? "input_required" : "completed");
        normalized.put("content", completedContent);
        normalized.put("passthrough_nodes", passthroughNodes);
        return normalized;
    }

    /**
     * 从 passthroughNodes 中提取 completedContent 兜底内容。
     * 优先取 answer envelope 的 output 字段，其次取 QA 节点的 text 字段，
     * 最后使用最后一个节点作为兜底。
     *
     * @param passthroughNodes the passthroughNodes value
     * @return the result
     */
    private String extractContentFromPassthroughNodes(List<String> passthroughNodes) {
        String completedContent = "";
        for (String node : passthroughNodes) {
            try {
                JsonNode nodeJson = OBJECT_MAPPER.readTree(node);
                String extracted = extractAnswerFromNode(nodeJson).orElse(null);
                if (extracted != null) {
                    return extracted;
                }
            } catch (JsonProcessingException e) {
                LOGGER.debug("VersatileInterruptRail: failed to parse passthrough node for answer extraction: {}",
                        e.getMessage());
            }
        }
        // fallback: 若未找到 answer 节点，使用最后一个 passthroughNode 作为兜底
        return extractLastNodeFallback(passthroughNodes, completedContent);
    }

    private Optional<String> extractAnswerFromNode(JsonNode nodeJson) {
        // 1) answer envelope（VersatileResponseExtractor截留成功时生成）
        String type = nodeJson.path("type").asText("");
        if ("answer".equals(type)) {
            String output = nodeJson.path("output").asText("");
            if (!output.isBlank()) {
                LOGGER.info("VersatileInterruptRail: extracted answer content from passthroughNodes, length={}",
                        output.length());
                return Optional.of(output);
            }
        }
        // 2) 直接透传的QA节点（result-node-name未配置/截留失败时）
        String nodeType = nodeJson.path("node_type").asText("");
        if ("QA".equals(nodeType)) {
            String text = nodeJson.path("text").asText("");
            if (!text.isBlank()) {
                LOGGER.info("VersatileInterruptRail: extracted QA node text from passthroughNodes, length={}",
                        text.length());
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private String extractLastNodeFallback(List<String> passthroughNodes, String completedContent) {
        String lastNode = passthroughNodes.get(passthroughNodes.size() - 1);
        try {
            JsonNode lastJson = OBJECT_MAPPER.readTree(lastNode);
            if (lastJson.isObject() || lastJson.isArray()) {
                LOGGER.info("VersatileInterruptRail: fallback to last passthroughNode as content, length={}",
                        lastNode.length());
                return lastNode;
            }
        } catch (JsonProcessingException e) {
            LOGGER.debug("[VersatileInterruptRail] passthroughNode fallback parse failed: {}", e.getMessage());
        }
        return completedContent;
    }

    private void storePassthroughNodes(AgentCallbackContext ctx, Map<String, Object> toolResult) {
        storePassthroughNodes(conversationId(ctx), toolResult);
    }

    private void storePassthroughNodes(String conversationId, Map<String, Object> toolResult) {
        if (toolResult == null) {
            return;
        }
        Object nodes = toolResult.get("passthrough_nodes");
        if (!(nodes instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (Object node : list) {
            if (node != null && !String.valueOf(node).isBlank()) {
                normalized.add(String.valueOf(node));
            }
        }
        passthroughBuffer.addAll(conversationId, normalized);
        LOGGER.info("VersatileInterruptRail: queued passthrough nodes conversationId={} count={}", conversationId,
                normalized.size());
    }

    private boolean isInputRequired(Map<String, Object> toolResult) {
        return toolResult != null && "input_required".equals(String.valueOf(toolResult.get("status")));
    }

    private ToolInterruptException inputRequiredInterrupt(AgentCallbackContext ctx, ToolCallInputs inputs,
            Map<String, Object> toolResult) {
        String toolCallId = inputs.getToolCall() != null && inputs.getToolCall().getId() != null
                ? inputs.getToolCall().getId()
                : "call_versatile";

        // 记录 interruptId，供续传完成时包装 InteractiveInput 恢复 call_versatile。
        passthroughBuffer.rememberInterruptId(conversationId(ctx), toolCallId);

        // 从 passthrough_nodes 中提取中断提示文本（如"请确认转账信息"）
        String message = extractPassthroughMessage(toolResult);
        LOGGER.info("VersatileInterruptRail: adapter requested user input, toolCallId={}, message={}", toolCallId,
                message);
        InterruptRequest request = InterruptRequest.builder().interruptId(toolCallId).message(message)
                .context(Map.of("tool", "call_versatile", "result", toolResult))
                .payloadSchema(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"),
                        "menu_type", Map.of("type", "string"), "menu_confirm", Map.of("type", "boolean"))))
                .build();
        return new ToolInterruptException(request, inputs.getToolCall());
    }

    /**
     * 从 toolResult 的 passthrough_nodes 中提取中断提示文本。
     * passthrough_nodes 中的每个元素是 JSON 字符串，如:
     * {"event":"message","data":{"text":"请确认转账信息","menu_type":"TRANSFER_MENU"}}
     *
     * @param toolResult the toolResult value
     * @return the result
     */

    @SuppressWarnings("unchecked")
    private String extractPassthroughMessage(Map<String, Object> toolResult) {
        if (toolResult == null) {
            return "";
        }
        Object nodes = toolResult.get("passthrough_nodes");
        if (!(nodes instanceof List<?> list) || list.isEmpty()) {
            // 回退到 content 字段
            Object content = toolResult.get("content");
            return content != null ? String.valueOf(content) : "";
        }
        for (Object node : list) {
            if (node == null || String.valueOf(node).isBlank()) {
                continue;
            }
            String nodeStr = String.valueOf(node);
            try {
                com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(nodeStr);

                // 优先取 data.text
                String text = parsed.path("data").path("text").asText("");
                if (!text.isBlank()) {
                    return text;
                }

                // 回退取 text
                text = parsed.path("text").asText("");
                if (!text.isBlank()) {
                    return text;
                }
            } catch (JsonProcessingException e) {
                // 非 JSON，直接返回原始文本
                if (!nodeStr.isBlank()) {
                    return nodeStr;
                }
            }
        }
        return "";
    }

    private String conversationId(AgentCallbackContext ctx) {
        return ctx.getSession() != null && ctx.getSession().getSessionId() != null
                ? ctx.getSession().getSessionId()
                : "call-versatile-spike";
    }

    private String extractA2aState(JsonNode result, String fallback) {
        String state = result.path("statusUpdate").path("status").path("state").asText("");
        if (state.isBlank()) {
            state = result.path("status").path("state").asText("");
        }
        return state.isBlank() ? fallback : state;
    }

    private String extractA2aArtifactText(JsonNode result) {
        JsonNode parts = result.path("artifactUpdate").path("artifact").path("parts");
        return extractPartsText(parts);
    }

    private String extractA2aStatusText(JsonNode result) {
        JsonNode parts = result.path("statusUpdate").path("status").path("message").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            parts = result.path("status").path("message").path("parts");
        }
        return extractPartsText(parts);
    }

    private String extractPartsText(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                text = part.path("content").asText("");
            }
            if (!text.isBlank()) {
                builder.append(text);
            }
        }
        return builder.toString();
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
                LOGGER.warn("VersatileInterruptRail: failed to parse tool arguments: {}", text);
            }
        }
        return args;
    }

    private Map<String, Object> buildInputs(Map<String, Object> args, AgentCallbackContext ctx) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);
        String query = String.valueOf(args.getOrDefault("query_description", ""));
        boolean queryFromArgs = !query.isBlank();
        if (query.isBlank()) {
            query = readCachedQuery(channelKey);
        }
        LOGGER.debug("[VersatileInterruptRail] query_description: fromArgs={}, fallbackToCache={}, finalLen={}",
                queryFromArgs, !queryFromArgs && !query.isBlank(), query.length());
        inputs.put("query", query);
        inputs.putAll(args);
        inputs.put("query_description", query);

        String inputKey = String.valueOf(args.getOrDefault("input_key", ""));
        if (!inputKey.isBlank()) {
            Object inputData = toolDataChannel.getObject(channelKey, inputKey).orElse(null);
            if (inputData != null) {
                inputs.put("input_data", inputData);
                inputs.put("business_data", inputData);
                LOGGER.info("VersatileInterruptRail: ToolDataChannel hit key={}, input_key={}", channelKey, inputKey);
            } else {
                LOGGER.warn("VersatileInterruptRail: ToolDataChannel miss key={}, input_key={}", channelKey, inputKey);
                inputs.put("input_data", Map.of());
                inputs.put("business_data", Map.of());
            }
        }
        return inputs;
    }

    private String readCachedQuery(ToolDataKey channelKey) {
        Object cached = toolDataChannel.getObject(channelKey, McpInterruptRail.VERSATILE_QUERY_KEY).orElse(null);
        if (cached instanceof String text) {
            return text;
        }
        if (cached instanceof Map<?, ?> map) {
            Object value = map.get("query_description");
            if (value == null) {
                value = map.get("query");
            }
            return value != null ? String.valueOf(value) : "";
        }

        // 兜底逻辑（方案2.3）：versatile_query 缓存缺失时，从 mcp_products_data 构造 rich query，
        // 确保 VersatileInterruptRail 委托请求包含足够的产品数据信息。
        Object mcpProductsData = toolDataChannel.getObject(channelKey,
                McpInterruptRail.DEFAULT_MCP_PRODUCTS_KEY).orElse(null);
        if (mcpProductsData instanceof Map<?, ?> productsMap && !productsMap.isEmpty()) {
            String constructedQuery = buildQueryFromProducts(productsMap).orElse(null);
            if (constructedQuery != null) {
                return constructedQuery;
            }

            // products 为空列表时，使用 map 的字符串表示作为兜底
            String dataStr = String.valueOf(productsMap);
            if (!dataStr.isBlank() && !"{}".equals(dataStr)) {
                LOGGER.warn(
                        "[VersatileInterruptRail] fallback query from mcp_products_data map "
                                + "(non-primary path), length={}",
                        dataStr.length());
                return dataStr;
            }
        }

        LOGGER.warn("[VersatileInterruptRail] readCachedQuery: all caches empty, returning empty query");
        return "";
    }

    private Optional<String> buildQueryFromProducts(Map<?, ?> productsMap) {
        Object productsList = productsMap.get("products");
        if (!(productsList instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder queryBuilder = new StringBuilder("理财产品推荐数据：");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> product)) {
                continue;
            }
            Object name = product.get("product_name");
            if (name == null) {
                continue;
            }
            queryBuilder.append(name);
            Object type = product.get("product_type");
            if (type != null) {
                queryBuilder.append("(").append(type).append(")");
            }
            queryBuilder.append("、");
        }
        String constructedQuery = queryBuilder.toString();
        if ("理财产品推荐数据：".equals(constructedQuery)) {
            return Optional.empty();
        }
        LOGGER.warn(
                "[VersatileInterruptRail] fallback query from mcp_products_data, "
                        + "length={} (non-primary path)",
                constructedQuery.length());
        return Optional.of(constructedQuery);
    }

    private String resolveUrl(String conversationId) {
        String resolved = versatileConfig.getUrl().replace("{conversation_id}", safePathSegment(conversationId));
        if (versatileConfig.getUrlVariables() != null) {
            for (Map.Entry<String, String> entry : versatileConfig.getUrlVariables().entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        StringBuilder url = new StringBuilder(resolved);
        if (versatileConfig.getQueryParams() != null && !versatileConfig.getQueryParams().isEmpty()) {
            boolean first = !resolved.contains("?");
            for (Map.Entry<String, String> entry : versatileConfig.getQueryParams().entrySet()) {
                url.append(first ? '?' : '&').append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }

    private String safePathSegment(String value) {
        return value.replace("/", "_").replace("\\", "_").replace("..", "_");
    }

    private boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private Duration parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return Duration.ofSeconds(30);
        }
        String value = timeout.trim().toLowerCase();
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.parse(timeout);
    }

    private String normalizeContent(String body) throws JsonProcessingException {
        String raw = body != null ? body.trim() : "";
        if (raw.isBlank()) {
            return "{}";
        }
        JsonNode sseResult = extractFromSse(raw);
        if (sseResult != null) {
            return OBJECT_MAPPER.writeValueAsString(sseResult);
        }
        String candidate = raw;
        JsonNode node = OBJECT_MAPPER.readTree(candidate);
        if (node.isObject() || node.isArray()) {
            JsonNode extracted = extractStandardJsonContent(node);
            return OBJECT_MAPPER.writeValueAsString(extracted);
        }
        throw new IllegalArgumentException("versatile content is not standard JSON object or array");
    }

    private JsonNode extractFromSse(String raw) throws JsonProcessingException {
        JsonNode lastJson = null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(payload);
            lastJson = node;
            if (node.has("text")) {
                JsonNode textResult = parseTextJson(node.get("text")).orElse(null);
                if (textResult != null) {
                    return textResult;
                }
            }
            if (node.has("data")) {
                JsonNode extracted = extractStandardJsonContent(node);
                if (extracted.isObject() || extracted.isArray()) {
                    return extracted;
                }
            }
        }
        return lastJson;
    }

    private JsonNode extractStandardJsonContent(JsonNode node) throws JsonProcessingException {
        if (node.has("content")) {
            return parseJsonNodeOrReturn(node.get("content"));
        }
        if (node.has("answer")) {
            return parseJsonNodeOrReturn(node.get("answer"));
        }
        if (node.has("data")) {
            JsonNode data = node.get("data");
            if (data.has("content")) {
                return parseJsonNodeOrReturn(data.get("content"));
            }
            if (data.has("answer")) {
                return parseJsonNodeOrReturn(data.get("answer"));
            }
            if (data.has("outputs")) {
                return parseJsonNodeOrReturn(data.get("outputs"));
            }
        }
        return node;
    }

    private Optional<JsonNode> parseTextJson(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(node.asText());
            return parsed.isObject() || parsed.isArray() ? Optional.of(parsed) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private JsonNode parseJsonNodeOrReturn(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (node.isObject() || node.isArray()) {
            return node;
        }
        if (node.isTextual()) {
            JsonNode parsed = OBJECT_MAPPER.readTree(node.asText());
            if (parsed.isObject() || parsed.isArray()) {
                return parsed;
            }
        }
        ObjectNode wrapper = OBJECT_MAPPER.createObjectNode();
        wrapper.set("value", node);
        return wrapper;
    }

    private String abbreviate(String value) {
        return abbreviate(value, 2000);
    }

    private String abbreviate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...(truncated)";
    }

    /**
     * 对 JSON 字符串中的敏感字段自动脱敏：银行卡号保留前4后4位、用户姓名保留首字、会话ID/Cookie掩码。
     *
     * @param json the json value
     * @return the result
     */
    private static String desensitizeSensitiveFields(String json) {
        if (json == null || "null".equals(json)) {
            return json;
        }

        // 银行卡号字段名匹配：保留前4后4位
        String result = json.replaceAll(
                "\"(bankCardNumber|payerCardNumber|payeeCardNumber|cardNum|cardNumber)\""
                        + "\\s*:\\s*\"(\\d{4})\\d+(\\d{4})\"",
                "\"$1\":\"$2****$3\"");

        // 中国银联卡号数字模式匹配：以62开头的16-19位连续数字，覆盖所有银联BIN
        result = BANK_CARD_NUMBER_PATTERN.matcher(result)
                .replaceAll(m -> m.group(1).substring(0, 4) + "****" + m.group(1).substring(m.group(1).length() - 4));

        // 用户姓名：保留首字+掩码
        result = result.replaceAll(
                "\"(wap_userName|wap_realName|userName|realName|customerName)\"\\s*:\\s*\"([^\"]{1})[^\"]+\"",
                "\"$1\":\"$2***\"");

        // 会话ID：仅保留前8位
        result = result.replaceAll("\"(wap_sessionId|sessionId)\"\\s*:\\s*\"([^\"]{8})[^\"]+\"", "\"$1\":\"$2***\"");

        // Cookie列表：不打印完整值
        result = result.replaceAll("\"(wapbCookieList|cookieList|cookies)\"\\s*:\\s*\"[^\"]+\"", "\"$1\":\"***\"");
        return result;
    }

    /**
     * 安全转 String：null 返回空串。
     *
     * @param value the value value
     * @return the result
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 安全转 int：null 或非数字返回默认值。
     *
     * @param value the value value
     * @param defaultValue the defaultValue value
     * @return the result
     */
    private int asInt(Object value, int defaultValue) {
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

    /**
     * 把任意 Map 归一为 String 键的 Map。
     *
     * @param source the source value
     * @return the result
     */
    private Map<String, Object> toStringKeyMap(Object source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
        }
        return result;
    }

    private Map<String, Object> failedResult(String error) {
        return Map.of("source", "versatile", "status", "failed", "error", error != null ? error : "unknown");
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /**
     * 工具调用后回调。
     *
     * <p>职责一：history_info 持久化（对齐 Python versatile_interrupt_rail.py 第 295-321 行）。</p>
     * <p>当前 adapter 归一化结果（{@code normalizeA2aAdapterResponse} 产出）顶层不含 history_info，
     * 此处为未来归一化脚本落地后的预留接入点：脚本输出含 history_info 时自动持久化到 ToolDataChannel，
     * 供下一次 call_mcp 的 buildSkillInput 读取，形成跨工具的会话级持久化闭环。
     * 持久化后从 toolResult/toolMsg 出口剔除该字段，避免 LLM 看到内部字段（对齐 Python 第 321 行）。</p>
     *
     * <p>职责二：记录 call_versatile 完成事件。</p>
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用和工具结果信息
     *
     */

    @Override
    /**
     * After tool call.
     *
     * @param ctx the ctx value
     */
    public void afterToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才需要识别 VA 委托工具。
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // 关键判断：只处理 call_versatile。
        if (!"call_versatile".equals(toolName)) {
            return;
        }

        // ── history_info 持久化 + 出口剔除 ──
        persistHistoryInfoIfPresent(ctx, inputs);

        LOGGER.info("VersatileInterruptRail: call_versatile completed, cascade result received");
    }

    /**
     * 检查 toolResult 顶层是否含 history_info，有则持久化到 ToolDataChannel 并从出口剔除。
     *
     * <p>对齐 Python 第 297-306 行持久化、第 321 行剔除。无 history_info 时仅打 persistence check 日志，
     * 与 Python 行为一致。持久化 key 与 {@link McpInterruptRail#HISTORY_INFO_KEY} 同名，
     * 共享同一四元组隔离，下一次 call_mcp 能读到。</p>
     *
     * @param ctx the ctx value
     * @param inputs the inputs value
     */

    private void persistHistoryInfoIfPresent(AgentCallbackContext ctx, ToolCallInputs inputs) {
        Object rawResult = inputs.getToolResult();
        if (!(rawResult instanceof Map<?, ?> rawMap)) {
            LOGGER.info("VersatileInterruptRail: persistence check history_info=not in result");
            return;
        }
        Map<String, Object> result = toStringKeyMap(rawMap);
        if (!result.containsKey(HISTORY_INFO_KEY)) {
            LOGGER.info("VersatileInterruptRail: persistence check history_info=not in result");
            return;
        }
        Object historyInfo = result.get(HISTORY_INFO_KEY);
        ToolDataKey channelKey = ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName);

        // 包装为 {"value": ...} 结构，与 McpInterruptRail 读取时的解包逻辑对齐。
        toolDataChannel.store(channelKey, HISTORY_INFO_KEY,
                Map.of("value", historyInfo != null ? historyInfo : List.of()));
        LOGGER.info("VersatileInterruptRail: persistence check history_info=persisted:{}",
                abbreviate(String.valueOf(historyInfo)));

        // 出口剔除：从 toolResult 与 toolMsg 中移除，避免 LLM 看到内部字段。
        result.remove(HISTORY_INFO_KEY);
        inputs.setToolResult(result);
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall != null && toolCall.getId() != null) {
            inputs.setToolMsg(ToolMessage.builder().content(toJson(result)).toolCallId(toolCall.getId()).build());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // pre-delegate guard
    // 对齐 Python versatile_interrupt_rail.py 第 421-525 行：
    // 在真正委托 adapter 之前，从 skill 脚本静态读取 PRE_DELEGATE_GUARD 配置，
    // 按 query_intent 维度计数，超限则终止当前 ReAct 流程，避免真实业务（如转账）被执行。
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 应用 pre-delegate guard。
     *
     * @param ctx  回调上下文
     * @param args call_versatile 工具参数（含 query_intent / query_response_analysis_scripts 等）
     * @return 超限判定；Optional.empty() 表示无 guard 或未超限，主流程继续
     *
     */

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

            // match 表示这条规则只对哪些 tool_args 生效，例如 {"query_intent": "快速转账"}。
            // 全部键值匹配才命中（等价 Python any(...) 取反的循环逻辑）。
            Map<String, Object> match = toStringKeyMap(ruleMap.get("match"));
            if (!match.isEmpty() && !matchesArgs(args, match)) {
                continue;
            }

            String ruleId = asString(ruleMap.getOrDefault("id", "default"));

            // state_key 用 command:ruleId 唯一标识，避免不同 skill 的同名规则互相干扰。
            String stateKey = GUARD_STATE_KEY_PREFIX + command + ":" + ruleId;
            int count = incrementGuardCount(channelKey, stateKey);
            int maxCalls = asInt(ruleMap.get("max_calls"), 0);
            LOGGER.info("VersatileInterruptRail: pre-delegate guard matched rule={}, count={}, limit={}, match={}",
                    ruleId, count, maxCalls, match);
            if (maxCalls > 0 && count > maxCalls) {
                String message = resolveGuardMessage(ruleMap);
                return Optional.of(new GuardDecision(true, ruleId, count, maxCalls, message));
            }
        }
        return Optional.empty();
    }

    /**
     * 静态读取脚本中的 {@code PRE_DELEGATE_GUARD = {...}} 配置。
     *
     * <p>对齐 Python {@code _load_pre_delegate_guard}（第 477-525 行）：不 import、不执行脚本，
     * 只静态解析字面量。Java 无 Python {@code ast} 模块，这里用括号配对截取字典字面量 + Jackson 解析，
     * 安全性与 {@code ast.literal_eval} 等价（不执行任意代码）。</p>
     *
     * <p>跳过条件（与 Python 一致）：命令中无 .py 脚本、skillsDir 为 null、脚本越界、脚本不存在、
     * 脚本中无 PRE_DELEGATE_GUARD 赋值。</p>
     *
     * @param command query_response_analysis_scripts 命令字符串
     * @return guard 配置 Map；空 Map 表示跳过
     *
     */

    private Map<String, Object> loadPreDelegateGuard(String command) {
        String script = extractScriptName(command);
        if (script == null || script.isBlank()) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, no script in command={}", command);
            return Map.of();
        }
        if (skillsDir == null) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, skillsDir is null");
            return Map.of();
        }
        Path scriptPath = skillsDir.resolve(script).toAbsolutePath().normalize();

        // 路径越界保护：解析后的路径必须在 skillsDir 之下。
        if (!scriptPath.startsWith(skillsDir)) {
            LOGGER.warn("VersatileInterruptRail: pre-delegate guard skipped, script outside skills dir, path={}",
                    scriptPath);
            return Map.of();
        }
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, script not found, path={}", scriptPath);
            return Map.of();
        }
        try {
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);
            String literal = extractAssignLiteral(source, "PRE_DELEGATE_GUARD").orElse(null);
            if (literal == null) {
                LOGGER.info("VersatileInterruptRail: pre-delegate guard skipped, PRE_DELEGATE_GUARD not found, path={}",
                        scriptPath);
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(literal, Map.class);
            int ruleCount = parsed.get("rules") instanceof List<?> l ? l.size() : 0;
            LOGGER.info("VersatileInterruptRail: pre-delegate guard loaded, path={}, rules={}", scriptPath, ruleCount);
            return parsed;
        } catch (IOException e) {
            LOGGER.warn("VersatileInterruptRail: pre-delegate guard parse failed, path={}, err={}", scriptPath,
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从命令字符串中提取脚本文件名（第一个以 .py 结尾的 Token）。
     * 对齐 Python 第 480 行 {@code next((part for part in command.split() if part.endswith(".py")), "")}。
     *
     * @param command the command value
     * @return the result
     */

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

    /**
     * 从 Python 源码中截取 {@code name = {...}} 赋值的字典字面量。
     *
     * <p>实现：定位 {@code name = } 后第一个 {@code {}，按括号配对截取到匹配的 {@code }}。
     * 字符串字面量内的括号不计入配对（感知单/双引号与转义），避免误截。
     * 替代 Python {@code ast.literal_eval}——Java 无 AST 模块，但 PRE_DELEGATE_GUARD 是
     * 标准 JSON-compatible dict 字面量，括号配对足够且不执行任意代码。</p>
     *
     * @param source the source value
     * @param name the name value
     * @return 字典字面量字符串（含外层花括号）；未找到返回 Optional.empty()
     */

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
                    i++; // 跳过转义字符
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
                // no-op: normal character, no bracket tracking needed
            }
        }
        return Optional.empty();
    }

    /**
     * 判断 tool_args 是否全部命中 match 中的键值。
     * 对齐 Python 第 430 行 {@code any(tool_args.get(key) != value for key, value in match.items())} 的反向逻辑
     * （Python 用 any+!= 表示"任一不匹配则跳过"，此处用 all+equals 表示"全部匹配才命中"）。
     *
     * @param args the args value
     * @param match the match value
     * @return the result
     */

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

    /**
     * 从 ToolDataChannel 读取并递增 guard 计数器。
     *
     * <p>对齐 Python {@code ctx.session.get_state(state_key)} + {@code update_state}。
     * Java 端 {@code ctx.getExtra()} 是 per-request、跨轮丢失，故用 ToolDataChannel（会话级四元组隔离）
     * 作为计数器存储，与 history_info / versatile_query 共享同一 channel。</p>
     *
     * @param channelKey the channelKey value
     * @param stateKey the stateKey value
     * @return the result
     */

    private int incrementGuardCount(ToolDataKey channelKey, String stateKey) {
        Object current = toolDataChannel.getObject(channelKey, stateKey).orElse(null);
        int count = (current instanceof Number n ? n.intValue() : 0) + 1;
        toolDataChannel.store(channelKey, stateKey, count);
        return count;
    }

    /**
     * 解析 guard 超限话术：优先取 {@code response_template_key} 对应的话术模板，
     * 缺失或为空则回落到规则的 {@code fallback_message}。
     *
     * <p>对齐 Python 第 444-449 行：
     * {@code text = scripts.get_response_template(template_key) or rule.get("fallback_message", "")}。
     * Java 端 {@code SysScriptsConfig.getTemplate(key)} 返回 Optional.empty() 表示缺失，等价 Python 的 falsy。</p>
     *
     * @param ruleMap the ruleMap value
     * @return the result
     */

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

    /**
     * guard 判定结果。blocked=true 表示超限需终止；其余字段仅用于日志。
     *
     * @param blocked the blocked value
     * @param ruleId the ruleId value
     * @param count the count value
     * @param maxCalls the maxCalls value
     * @param message the message value
     * @return the result
     */
    private record GuardDecision(boolean blocked, String ruleId, int count, int maxCalls, String message) {
    }

    /**
     * 会话级 Versatile USER 透传缓冲。
     *
     * <p>Rail 在 adapter 响应解析阶段写入完整 message JSON；
     * {@link com.huawei.ascend.edp.handler.EdpaRuntimeHandler} 的流式迭代器
     * 在 DeepAgent 帧之间按 FIFO 刷出，避免 node_type/menu_type 等字段被降维丢失。</p>
     *
     */

    public static final class VersatilePassthroughBuffer {
        private final Map<String, Deque<String>> nodesByConversation = new HashMap<>();

        /**
         * 中断时的 toolCallId，续传完成后用于构造 InteractiveInput。
         *
         * @return the result
         */
        private final Map<String, String> interruptIdsByConversation = new HashMap<>();

        /**
         * Add all.
         *
         * @param conversationId the conversationId value
         * @param nodes the nodes value
         */
        public void addAll(String conversationId, Collection<String> nodes) {
            if (conversationId == null || conversationId.isBlank() || nodes == null || nodes.isEmpty()) {
                return;
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.computeIfAbsent(conversationId,
                        ignored -> new ArrayDeque<>());
                for (String node : nodes) {
                    if (node != null && !node.isBlank()) {
                        queue.addLast(node);
                    }
                }
            }
        }

        /**
         * Poll.
         *
         * @param conversationId the conversationId value
         * @return the result
         */
        public Optional<String> poll(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return Optional.empty();
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.get(conversationId);
                if (queue == null) {
                    return Optional.empty();
                }
                String node = queue.pollFirst();
                if (queue.isEmpty()) {
                    nodesByConversation.remove(conversationId);
                }
                return Optional.ofNullable(node);
            }
        }

        /**
         * Checks whether pending is present.
         *
         * @param conversationId the conversationId value
         * @return the result
         */
        public boolean hasPending(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return false;
            }
            synchronized (nodesByConversation) {
                Deque<String> queue = nodesByConversation.get(conversationId);
                return queue != null && !queue.isEmpty();
            }
        }

        /**
         * Clears all data for the given key.
         *
         * @param conversationId the conversationId value
         */
        public void clear(String conversationId) {
            if (conversationId != null && !conversationId.isBlank()) {
                synchronized (nodesByConversation) {
                    nodesByConversation.remove(conversationId);
                }
            }
        }

        /**
         * Remember interrupt id.
         *
         * @param conversationId the conversationId value
         * @param interruptId the interruptId value
         */
        public void rememberInterruptId(String conversationId, String interruptId) {
            if (conversationId == null || conversationId.isBlank() || interruptId == null || interruptId.isBlank()) {
                return;
            }
            synchronized (interruptIdsByConversation) {
                interruptIdsByConversation.put(conversationId, interruptId);
            }
        }

        /**
         * Poll interrupt id.
         *
         * @param conversationId the conversationId value
         * @return the result
         */
        public Optional<String> pollInterruptId(String conversationId) {
            if (conversationId == null || conversationId.isBlank()) {
                return Optional.empty();
            }
            synchronized (interruptIdsByConversation) {
                return Optional.ofNullable(interruptIdsByConversation.remove(conversationId));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 归一化脚本执行（对齐 Python versatile_interrupt_rail.py _sandbox_normalize）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 归一化脚本输出的封装。
     */
    private static class NormalizeOutput {
        String status; // "success" | "failure"；null 表示脚本未执行或失败
        Map<String, Object> data; // 归一化后的业务数据（ui_notice 已 pop）
        Map<String, Object> uiNotice; // 可选：脚本注入的 ui_notice
    }

    /**
     * 从 tool args 中解析归一化脚本路径。
     * LLM 传入的 query_response_analysis_scripts 可能是 JSON 数组（如 ["python xxx.py"]）或纯字符串，
     * 此方法兼容两种格式，取第一个非空元素作为脚本路径。
     *
     * @param raw the raw value
     * @return the result
     */

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

    /**
     * 执行归一化脚本（通用，不含任何 skill 专有字段名）。
     *
     * <p>对齐 Python {@code _sandbox_normalize}：
     * 1. 构造 SKILL_INPUT 环境变量（JSON）
     * 2. ProcessBuilder 执行 python script
     * 3. 解析 stdout → {@code [status, normalized]}
     * 4. 提取并 pop normalized.ui_notice</p>
     *
     * <p>脚本不存在或执行失败时降级透传，不影响主流程。</p>
     *
     * @param command        query_response_analysis_scripts 命令字符串
     * @param toolResult     callVersatile 的原始返回
     * @param versatileArgs  the versatileArgs value
     * @param ctx            回调上下文
     * @return 归一化输出
     */

    private NormalizeOutput invokeNormalizeScript(String command, Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx) {
        NormalizeOutput fallback = new NormalizeOutput();
        fallback.status = null;
        fallback.data = toolResult;
        fallback.uiNotice = null;

        // 1. 提取脚本路径
        String scriptName = extractScriptName(command);
        if (scriptName.isBlank()) {
            LOGGER.info("VersatileInterruptRail: normalize skipped, no .py in command={}", command);
            return fallback;
        }

        // ── SANDBOX 路径：归一化脚本在沙箱容器中执行 ──
        if (sysOp != null && sysOp.getMode() == OperationMode.SANDBOX && skillDeployPath != null) {
            return invokeNormalizeSandbox(command, toolResult, versatileArgs, ctx, fallback);
        }

        // ── LOCAL 路径：归一化脚本在本地 ProcessBuilder 执行 ──
        return invokeNormalizeLocal(toolResult, versatileArgs, ctx, fallback, scriptName);
    }

    /**
     * SANDBOX 路径：归一化脚本在沙箱容器中执行。
     *
     * @param command the command value
     * @param toolResult the toolResult value
     * @param versatileArgs the versatileArgs value
     * @param ctx the ctx value
     * @param fallback the fallback value
     * @return the result
     */
    private NormalizeOutput invokeNormalizeSandbox(String command, Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx, NormalizeOutput fallback) {
        // SANDBOX模式：不验证本地文件存在性（脚本在沙箱容器中，本地不一定有）
        // SessionContextHolder 绑定：使 {session_id} 占位符自动替换为 conversationId，
        // 确保每个会话使用独立沙箱容器（对齐 SandboxInterruptRail.resolveInterrupt()）
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

            LOGGER.info("VersatileInterruptRail: normalize via sandbox, command={}, cwd={}, governed={}", command,
                    skillDeployPath, decoratedClient != null);

            // 核心修改：优先使用 decoratedClient（需求2路径：经过治理装饰）
            ExecuteCmdResult result;
            if (decoratedClient != null) {
                result = decoratedClient.shell().executeCmd(command, skillDeployPath,
                        ScriptConstants.SANDBOX_TIMEOUT_SECONDS, env, null);
            } else {
                result = sysOp.shell().executeCmd(command, skillDeployPath, ScriptConstants.SANDBOX_TIMEOUT_SECONDS,
                        env, null);
            }

            return adaptNormalizeResult(result, fallback);
        } catch (com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException e) {
            LOGGER.warn("VersatileInterruptRail: normalize sandbox error, code={}, msg={}", e.getErrorCode(),
                    e.getMessage());
            return fallback; // 沙箱不可用 -> 降级透传原始数据（不降级到本地执行）
        } catch (JsonProcessingException e) {
            LOGGER.warn("VersatileInterruptRail: normalize sandbox exception, err={}", e.getMessage());
            return fallback;
        } finally {
            SessionContextHolder.setCurrentSession(previousSession);
        }
    }

    /**
     * LOCAL 路径：归一化脚本在本地 ProcessBuilder 执行。
     *
     * @param toolResult the toolResult value
     * @param versatileArgs the versatileArgs value
     * @param ctx the ctx value
     * @param fallback the fallback value
     * @param scriptName the scriptName value
     * @return the result
     */
    private NormalizeOutput invokeNormalizeLocal(Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx, NormalizeOutput fallback,
            String scriptName) {
        Path scriptPath = skillsDir.resolve(scriptName).toAbsolutePath().normalize();
        if (!scriptPath.startsWith(skillsDir) || !Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            LOGGER.info("VersatileInterruptRail: normalize skipped, script not found, path={}", scriptPath);
            return fallback;
        }

        try {
            Map<String, Object> skillInput = buildNormalizeSkillInput(toolResult, versatileArgs, ctx);
            String skillInputJson = OBJECT_MAPPER.writeValueAsString(skillInput);

            // ProcessBuilder 使用相对脚本名 + skillsDir 作为工作目录
            ProcessBuilder pb = new ProcessBuilder("python", scriptName);
            pb.directory(skillsDir.toFile());
            pb.environment().put("SKILL_INPUT", skillInputJson);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(ScriptConstants.SANDBOX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("VersatileInterruptRail: normalize timeout, script={}", scriptPath);
                return fallback;
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.warn("VersatileInterruptRail: normalize failed exit={}, script={}, stdout={}", exitCode,
                        scriptPath, abbreviate(stdout));
                return fallback;
            }
            return parseNormalizeStdout(stdout, fallback);
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("VersatileInterruptRail: normalize exception, script={}, err={}", scriptPath, e.getMessage());
            return fallback;
        }
    }

    /**
     * 从 toolResult 中提取 business_data。
     * Versatile 返回的 content 可能是 JSON 字符串，需要解析。
     *
     * @param toolResult the toolResult value
     * @return the result
     */

    private Map<String, Object> extractBusinessData(Map<String, Object> toolResult) {
        Object content = toolResult.get("content");
        if (content instanceof String s && !s.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(s);
                if (node.isObject()) {
                    return OBJECT_MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                }
            } catch (JsonProcessingException e) {
                // content 不是 JSON，降级
            }
        }
        return toolResult;
    }

    /**
     * 构造归一化脚本 SKILL_INPUT（对齐 Python versatile_interrupt_rail.py _build_skill_input 行520-540）。
     *
     * <p>Python版本构造5字段：query_intent + query_description + business_data +
     * notice_context(透传) + history_info。Java版补齐3个缺失字段，
     * 确保归一化脚本在沙箱环境下能获取完整输入数据。</p>
     *
     * @param toolResult the toolResult value
     * @param versatileArgs the versatileArgs value
     * @param ctx the ctx value
     * @return the result
     */

    private Map<String, Object> buildNormalizeSkillInput(Map<String, Object> toolResult,
            Map<String, Object> versatileArgs, AgentCallbackContext ctx) {
        Map<String, Object> skillInput = new LinkedHashMap<>();
        skillInput.put("business_data", extractBusinessData(toolResult));

        // ── Python对标补齐：query_intent + query_description + notice_context ──
        skillInput.put("query_intent", versatileArgs.getOrDefault("query_intent", ""));
        skillInput.put("query_description", versatileArgs.getOrDefault("query_description", ""));

        // notice_context 透传到脚本，不在 rail 层解析（对齐 Python "保证双向透明"）
        Object noticeContext = versatileArgs.get("notice_context");
        if (noticeContext != null) {
            skillInput.put("notice_context", noticeContext);
        }

        // history_info 跨 Skill 传递
        Object historyInfo = toolDataChannel.getObject(ToolDataKeyFactory.fromContext(ctx, edpConfig, agentName),
                HISTORY_INFO_KEY).orElse(null);
        skillInput.put("history_info", historyInfo instanceof Map<?, ?> m ? m.get("value") : List.of());
        return skillInput;
    }

    /**
     * 适配沙箱 ExecuteCmdResult → NormalizeOutput。
     *
     * @param result the result value
     * @param fallback the fallback value
     * @return the result
     */
    private NormalizeOutput adaptNormalizeResult(ExecuteCmdResult result, NormalizeOutput fallback) {
        if (result == null || result.getData() == null) {
            return fallback;
        }
        int exitCode = result.getData().getExitCode() != null ? result.getData().getExitCode() : -1;
        if (exitCode != 0) {
            LOGGER.warn("VersatileInterruptRail: normalize sandbox exit={}, stderr={}", exitCode,
                    result.getData().getStderr());
            return fallback;
        }
        String stdout = result.getData().getStdout() != null ? result.getData().getStdout() : "";
        if (stdout.isBlank()) {
            return fallback;
        }
        return parseNormalizeStdout(stdout, fallback);
    }

    /**
     * 解析归一化脚本 stdout（SANDBOX/LOCAL 共用）。兼容两种格式：
     * <ul>
     *   <li>① [status, normalized] — 数组格式</li>
     *   <li>② {normalized}        — 单对象格式（0712版其他同事新增兼容）</li>
     * </ul>
     *
     * @param stdout the stdout value
     * @param fallback the fallback value
     * @return the result
     */

    private NormalizeOutput parseNormalizeStdout(String stdout, NormalizeOutput fallback) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(stdout);
            String status;
            Map<String, Object> normalized;

            if (root.isArray() && root.size() >= 2) {
                // 数组格式：[status, normalized]
                status = root.get(0).asText("");
                normalized = OBJECT_MAPPER.convertValue(root.get(1),
                        new TypeReference<LinkedHashMap<String, Object>>() {
                        });
            } else if (root.isObject()) {
                // 单对象格式：status 从 result.status 字段提取（0712版兼容）
                normalized = OBJECT_MAPPER.convertValue(root, new TypeReference<LinkedHashMap<String, Object>>() {
                });
                status = String.valueOf(normalized.getOrDefault("status", ""));
            } else {
                LOGGER.warn("VersatileInterruptRail: normalize unexpected output, stdout={}", abbreviate(stdout));
                return fallback;
            }

            // 提取并 pop ui_notice
            NormalizeOutput output = new NormalizeOutput();
            output.status = status;
            output.data = normalized;
            Object uiNoticeRaw = normalized.remove("ui_notice");
            if (uiNoticeRaw instanceof Map<?, ?> um) {
                output.uiNotice = toStringKeyMap(um);
            }

            LOGGER.info("VersatileInterruptRail: normalize done, status={}, uiNotice={}, keys={}", status,
                    output.uiNotice != null ? output.uiNotice.get("key") : "null", normalized.keySet());
            return output;
        } catch (JsonProcessingException e) {
            LOGGER.warn("VersatileInterruptRail: normalize parse exception, err={}", e.getMessage());
            return fallback;
        }
    }

    /**
     * 通用话术索引（对齐 Python versatile_interrupt_rail.py L213-228）。
     *
     * <p>status="success" → index=0, status="failure" → index=1。
     * 不含任何 skill 专有字段名。</p>
     *
     * @param keysObj response_template_keys 参数值（JSON 字符串或 List）
     * @param status  归一化脚本返回的 status
     * @return 话术 key；Optional.empty() 表示无法索引
     *
     */

    private Optional<String> resolveTemplateKey(Object keysObj, String status) {
        if (keysObj == null || status == null || status.isBlank()) {
            return Optional.empty();
        }
        List<String> keys;
        try {
            if (keysObj instanceof String s) {
                keys = OBJECT_MAPPER.readValue(s, new TypeReference<List<String>>() {
                });
            } else if (keysObj instanceof List<?> l) {
                keys = new ArrayList<>();
                for (Object item : l) {
                    keys.add(String.valueOf(item));
                }
            } else {
                return Optional.empty();
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("VersatileInterruptRail: failed to parse response_template_keys={}", keysObj);
            return Optional.empty();
        }
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        int index = "success".equals(status) ? 0 : 1;
        return index < keys.size() ? Optional.of(keys.get(index)) : Optional.empty();
    }
}

