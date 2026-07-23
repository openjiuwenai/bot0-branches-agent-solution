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

import com.huawei.ascend.edp.config.SandboxConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.sandbox.ContainerManager;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxOperationSupport;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱中断委派 Rail -- 基于 BaseInterruptRail 的中断模式实现沙箱双路径路由。
 *
 * <p>工作模式：</p>
 * <ul>
 *     <li>SANDBOX 模式：intercept → 沙箱委派执行 → reject(result) 注回结果</li>
 *     <li>LOCAL 模式：approve() → 放行到原始工具执行（McpInterruptRail ProcessBuilder）</li>
 * </ul>
 *
 * <p>与 RemoteA2aInterruptRail 的"拦截→委派→reject注回"模式完全一致。</p>
 *
 * @since 2024-01-01
 */

public class SandboxInterruptRail extends BaseInterruptRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxInterruptRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 拦截 call_mcp 工具调用。 */
    private static final Set<String> TARGET_TOOLS = Set.of("call_mcp");

    private final SysOperation sysOp;
    private final SandboxConfig sandboxConfig;
    private final SandboxGatewayConfig gatewayConfig;

    /** 会话级沙箱容器管理器（可为 null，仅 containerScope=SESSION 时使用） */
    private final ContainerManager containerManager;

    /** 治理装饰 SandboxClient（需求2路径，可为 null）。非 null 时优先使用其 shell() 以获得熔断/重试/审计。 */
    private final SandboxClient decoratedClient;

    public SandboxInterruptRail(SysOperation sysOp, SandboxConfig config, SandboxGatewayConfig gatewayConfig) {
        this(sysOp, config, gatewayConfig, null, null);
    }

    public SandboxInterruptRail(SysOperation sysOp, SandboxConfig config, SandboxGatewayConfig gatewayConfig,
            SandboxClient decoratedClient) {
        this(sysOp, config, gatewayConfig, null, decoratedClient);
    }

    public SandboxInterruptRail(SysOperation sysOp, SandboxConfig config, SandboxGatewayConfig gatewayConfig,
            ContainerManager containerManager, SandboxClient decoratedClient) {
        super(TARGET_TOOLS);
        this.sysOp = sysOp;
        this.sandboxConfig = config;
        this.gatewayConfig = gatewayConfig;
        this.containerManager = containerManager;
        this.decoratedClient = decoratedClient;
    }

    /**
     * 核心中断决策方法 -- BaseInterruptRail 模板方法回调。
     *
     * <p>SANDBOX 模式：在沙箱中执行脚本，reject(sandboxResult) 注回结果。
     * LOCAL 模式：approve() 放行，工具调用继续到 McpInterruptRail。</p>
     */

    @Override
    /** Resolves the interrupt decision for the tool call. */
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        if (sysOp.getMode() == OperationMode.SANDBOX) {
            // 会话级隔离：将当前 Session 绑定到线程上下文，
            // 使 SandboxOperationSupport.resolveIsolationKey() 中的 {session_id} 占位符
            // 自动替换为 conversationId，ContainerManager 为每个 conversationId 维护独立 SandboxClient
            Session previousSession = SessionContextHolder.getCurrentSession();
            if (ctx.getSession() != null) {
                SessionContextHolder.setCurrentSession(ctx.getSession());
            }

            try {
                // SANDBOX 模式：拦截工具调用，在沙箱中执行
                Map<String, Object> sandboxResult = executeInSandbox(ctx, toolCall);
                if (!sandboxResult.isEmpty()) {
                    return reject(sandboxResult);
                }

                // 沙箱执行失败但 fallback_on_failure 未生效时，降级到本地
                LOGGER.warn("[EDP-SANDBOX] Sandbox exec failed, approving for local fallback");
                return approve();
            } finally {
                // 恢复原有 Session 上下文
                if (previousSession != null) {
                    SessionContextHolder.setCurrentSession(previousSession);
                } else {
                    SessionContextHolder.setCurrentSession(null);
                }
            }
        }

        // LOCAL 模式：放行到原始工具执行（McpInterruptRail ProcessBuilder 或 LocalShellOperation）
        return approve();
    }

    /** 在沙箱中执行脚本 */
    private Map<String, Object> executeInSandbox(AgentCallbackContext ctx, ToolCall toolCall) {
        try {
            if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
                return Collections.emptyMap();
            }

            // 1. 从工具参数中提取脚本信息
            Map<String, Object> args = McpInterruptRail.extractScriptArgs(inputs);
            String scriptCommand = String.valueOf(args.getOrDefault("script_command", ""));
            if (scriptCommand.isBlank()) {
                LOGGER.warn("[EDP-SANDBOX] script_command is blank, falling back to local");
                return Collections.emptyMap();
            }

            Map<String, Object> scriptParams = McpInterruptRail.extractScriptParams(args);
            String argumentsJson = OBJECT_MAPPER.writeValueAsString(scriptParams);

            // 2. SANDBOX模式下：保持 scriptCommand 为 LLM 传入的相对路径，
            //    通过 cwd 参数设置沙箱内工作目录（利用框架原生 cwd 机制，无需 cd 命令）
            String command = scriptCommand;
            String sandboxCwd = sandboxConfig.getSkillDeployPath(); // "/app/skills"

            // 4. 构建环境变量
            Map<String, String> env = buildEnvironmentMap(argumentsJson, ctx);

            // 5. 通过 SysOperation 统一入口执行（自动路由到 SandboxShellOperation）
            int timeout = sandboxConfig.getExecTimeoutSeconds();
            LOGGER.info("[EDP-SANDBOX] Executing in sandbox: command={}, cwd={}, governed={}", command, sandboxCwd,
                    decoratedClient != null);

            // 核心修改：优先使用 decoratedClient（需求2路径：经过 DecoratingSandboxClient → ExternalCallExecutor 熔断/重试/审计）
            ExecuteCmdResult result;
            if (decoratedClient != null) {
                result = decoratedClient.shell().executeCmd(command, sandboxCwd, timeout, env, null);
            } else {
                result = sysOp.shell().executeCmd(command, sandboxCwd, timeout, env, null);
            }

            // 6. 适配结果格式
            return adaptResult(result);
        } catch (com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException e) {
            // 需求2 的结构化错误码映射（5.6.4）
            switch (e.getErrorCode()) {
                case SANDBOX_OUTBOUND_CALL_FAILED :
                    LOGGER.warn("[EDP-SANDBOX] Outbound call failed: {}", e.getMessage());
                    break;
                case SANDBOX_CIRCUIT_OPEN :
                    LOGGER.warn("[EDP-SANDBOX] Circuit breaker open, falling back to local");
                    break;
                case SANDBOX_TIMEOUT :
                    LOGGER.warn("[EDP-SANDBOX] Sandbox timeout: {}", e.getMessage());
                    break;
                case SANDBOX_RETRY_INTERRUPTED :
                    LOGGER.warn("[EDP-SANDBOX] Retry interrupted: {}", e.getMessage());
                    break;
                default :
                    LOGGER.warn("[EDP-SANDBOX] External adapter error: code={}, message={}", e.getErrorCode(),
                            e.getMessage());
                    break;
            }
            return Collections.emptyMap(); // null -> approve() -> 本地降级
        } catch (JsonProcessingException e) {
            LOGGER.warn("[EDP-SANDBOX] Sandbox execution exception: {}", e.getMessage());
            return Collections.emptyMap(); // null -> 降级到 approve()（本地执行）
        }
    }

    /** 适配 ExecuteCmdResult → Map<String, Object>（对齐 McpInterruptRail.parseScriptOutput 格式） */
    private Map<String, Object> adaptResult(ExecuteCmdResult result) {
        if (result == null || result.getData() == null) {
            return McpInterruptRail.failedResult("sandbox returned null result");
        }

        int exitCode = result.getData().getExitCode() != null ? result.getData().getExitCode() : -1;
        String stdout = result.getData().getStdout() != null ? result.getData().getStdout() : "";
        String stderr = result.getData().getStderr() != null ? result.getData().getStderr() : "";

        if (exitCode != 0) {
            LOGGER.warn("[EDP-SANDBOX] Sandbox exitCode={}, stderr={}", exitCode, stderr);
            return McpInterruptRail.failedResult("sandbox exitCode=" + exitCode + ", stderr=" + stderr);
        }

        if (stdout.isBlank()) {
            return McpInterruptRail.failedResult("sandbox stdout is empty");
        }

        // 复用 McpInterruptRail.lastJsonLine 逻辑解析最后一行 JSON
        String lastJson = McpInterruptRail.lastJsonLine(stdout);
        if (lastJson == null || lastJson.isBlank()) {
            return McpInterruptRail.failedResult("failed to parse sandbox stdout JSON");
        }

        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(lastJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            parsed.putIfAbsent("result_key", McpInterruptRail.DEFAULT_MCP_PRODUCTS_KEY);
            LOGGER.info("[EDP-SANDBOX] Sandbox result parsed, keys={}", parsed.keySet());
            return parsed;
        } catch (JsonProcessingException e) {
            return McpInterruptRail.failedResult("sandbox JSON parse error: " + e.getMessage());
        }
    }

    /** 构建 SKILL_INPUT + PYTHONIOENCODING + MCP 认证环境变量 */
    private Map<String, String> buildEnvironmentMap(String argumentsJson, AgentCallbackContext ctx) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SKILL_INPUT", argumentsJson);
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    /**
     * 释放会话级沙箱（阶段二新增）。
     *
     * <p>会话结束时调用，释放该 conversationId 对应的沙箱容器。
     * 框架的 ActiveStreamRegistry.awaitDrain() 确保活跃流排空后再调用此方法。</p>
     *
     * @param sessionId 会话 ID（conversationId）
     */

    public void releaseSession(String sessionId) {
        if (containerManager == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // 通过 {session_id} 模板解析出与 resolveInterrupt 中相同的 isolationKey
            String keyTemplate = SandboxOperationSupport.computeIsolationKey(gatewayConfig);
            String isolationKey = SandboxOperationSupport.resolveIsolationKeyTemplate(keyTemplate);

            // 如果 isolationKey 仍包含 {session_id}（无 SessionContextHolder 时），手动替换
            if (isolationKey.contains("{session_id}")) {
                isolationKey = isolationKey.replace("{session_id}", sessionId);
            }
            boolean released = containerManager.release(isolationKey);
            LOGGER.info("[EDP-SANDBOX] Session sandbox released: sessionId={}, isolationKey={}, released={}", sessionId,
                    isolationKey, released);
        } catch (IllegalStateException | IllegalArgumentException e) {
            LOGGER.warn("[EDP-SANDBOX] Failed to release session sandbox: sessionId={}, error={}", sessionId,
                    e.getMessage());
        }
    }
}
