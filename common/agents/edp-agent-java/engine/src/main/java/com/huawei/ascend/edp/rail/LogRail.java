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

import com.huawei.ascend.edp.config.EdpConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * EDPAgent 观测日志 Rail。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>记录模型调用前的消息数量和最后一条消息。</li>
 *     <li>记录模型调用后的响应对象。</li>
 *     <li>记录模型 token 使用量，支撑端到端穿刺证据收集。</li>
 *     <li>记录工具调用完成事件。</li>
 *     <li>afterModelCall 中对 tool_call.arguments 做非法 JSON 自动修复（对齐 Python LogRail._repair_tool_call_arguments）。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #LogRail(EdpConfig)}：创建日志 Rail。</li>
 *     <li>{@link #beforeModelCall(AgentCallbackContext)}：模型调用前回调。</li>
 *     <li>{@link #afterModelCall(AgentCallbackContext)}：模型调用后回调。</li>
 *     <li>{@link #afterToolCall(AgentCallbackContext)}：工具调用后回调。</li>
 * </ul>
 *
 * <p><b>排查指引（现场联调 / 问题定界定位）：</b></p>
 * <pre>
 *   grep "[EDP-LLM-EMPTY]"      → 快速定位 LLM 空 answer
 *   grep "[EDP-LLM-RAW]"        → LLM 产出时间线（全文不截断，需 DEBUG 级别）
 *   grep "[LogRail] auto-repaired" → LLM streaming JSON 不闭合自动修复
 *   grep "[LogRail] FAILED to repair" → JSON 修复失败
 *   grep "E2E_MODEL_OUTPUT"     → 模型响应摘要
 *   grep "E2E_MODEL_USAGE"      → Token 消耗统计
 * </pre>
 *
 * @since 2024-01-01
 *
 */

public class LogRail extends AgentRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * EDP 专有配置，当前预留给后续日志脱敏、采样和开关控制使用。
     *
     */

    private final EdpConfig edpConfig;

    /**
     * 构造日志 Rail。
     *
     * @param edpConfig EDP 专有配置
     *
     * @return result
     *
     */

    public LogRail(EdpConfig edpConfig) {
        this.edpConfig = edpConfig;

        // 日志 Rail 使用较低优先级，尽量在其他业务 Rail 完成处理后记录最终上下文。
        setPriority(10);
    }

    /**
     * 模型调用前回调。
     *
     * @param ctx OpenJiuwen 回调上下文，包含模型入参
     *
     */

    @Override
    /**
     * Before model call.
     *
     * @param ctx the ctx value
     */
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 关键判断：只有模型调用上下文才记录模型输入。
        if (ctx.getInputs() instanceof ModelCallInputs inputs) {
            Object lastMessage = inputs.getMessages().isEmpty()
                    ? null
                    : inputs.getMessages().get(inputs.getMessages().size() - 1);
            LOGGER.info("E2E_MODEL_INPUT messageCount={ // no-op }, lastMessageType={ // no-op }, lastMessageHash={ // no-op }",
                    inputs.getMessages().size(), lastMessage != null ? lastMessage.getClass().getSimpleName() : "null",
                    lastMessage != null ? Integer.toHexString(System.identityHashCode(lastMessage)) : "null");
        }
    }

    /**
     * 模型调用后回调。
     *
     * <p>除记录响应和 usage 外，还对 {@code tool_call.arguments} 做非法 JSON 自动修复。
     * LLM streaming 偶发会少生成 args 末尾的闭合 {@code }}，导致下一轮 LLM 调用 messages
     * 带着坏 args 触发严格网关 400。此修复在 ReActAgent 持久化 AssistantMessage 之前
     * 执行，确保持久化的是合法 JSON。</p>
     *
     * @param ctx OpenJiuwen 回调上下文，包含模型响应
     *
     */

    @Override
    /**
     * After model call.
     *
     * @param ctx the ctx value
     */
    public void afterModelCall(AgentCallbackContext ctx) {
        // 关键判断：非模型调用上下文只记录完成事件，不读取模型响应。
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            LOGGER.info("[LogRail] model call completed, model response received");
            return;
        }
        Object response = inputs.getResponse();
        LOGGER.info("E2E_MODEL_OUTPUT responseType={ // no-op }, finishReason={ // no-op }", response.getClass().getSimpleName(),
                response instanceof AssistantMessage am ? am.getFinishReason() : "N/A");

        // 对齐 Python agent.py L1321-1326: [EDP-LLM-EMPTY] 空 response 快速过滤
        if (response instanceof AssistantMessage msg
                && (msg.getContent() == null || String.valueOf(msg.getContent()).isBlank())) {
            LOGGER.warn("[EDP-LLM-EMPTY] empty final answer: finishReason={ // no-op }, toolCalls={ // no-op }, contentIsNull={ // no-op }",
                    msg.getFinishReason(), msg.getToolCalls() != null ? msg.getToolCalls().size() : 0,
                    msg.getContent() == null);
        }

        // 对齐 Python agent.py L1116-1122: [EDP-LLM-RAW] 输出 content 摘要而非对象哈希
        if (response instanceof AssistantMessage rawMsg) {
            String content = String.valueOf(rawMsg.getContent());
            int contentLen = content != null && !"null".equals(content) ? content.length() : 0;
            String preview = contentLen > 0 ? content.substring(0, Math.min(contentLen, 500)) : "";
            LOGGER.debug("[EDP-LLM-RAW] answer event: contentLen={ // no-op }, finishReason={ // no-op }, contentPreview={ // no-op }", contentLen,
                    rawMsg.getFinishReason(), preview);
        } else {
            LOGGER.debug("[EDP-LLM-RAW] answer event: responseType={ // no-op }", response.getClass().getSimpleName());
        }

        // 关键判断：只有 AssistantMessage 响应才可能携带 usage 元数据和 tool_calls。
        if (response instanceof AssistantMessage assistantMessage) {
            repairToolCallArguments(assistantMessage);
            UsageMetadata usage = assistantMessage.getUsageMetadata();
            if (usage != null) {
                LOGGER.info("E2E_MODEL_USAGE inputTokens={ // no-op }, outputTokens={ // no-op }, totalTokens={ // no-op }, model={ // no-op }",
                        usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(), usage.getModelName());
            } else {
                LOGGER.info("E2E_MODEL_USAGE null");
            }
        }
    }

    /**
     * 工具调用后回调。
     *
     * @param ctx OpenJiuwen 回调上下文，包含工具调用信息
     *
     */

    @Override
    /**
     * After tool call.
     *
     * @param ctx the ctx value
     */
    public void afterToolCall(AgentCallbackContext ctx) {
        // 关键判断：只有工具调用上下文才记录工具名。
        if (ctx.getInputs() instanceof ToolCallInputs inputs) {
            LOGGER.info("LogRail: tool call completed, toolName={ // no-op }", inputs.getToolName());
        }
    }

    /**
     * 对 response.tool_calls 里 arguments 不合法的 JSON 做 in-place 自动修复。
     *
     * <p>对齐 Python {@code LogRail._repair_tool_call_arguments}：遍历未闭合的 {@code { // no-op } / {@code [}
     * 栈底，按相反顺序补齐 {@code }} / {@code ]}。对"丢末尾 }"这种最常见的 LLM streaming quirk 100% 生效。
     * 命中时打 WARNING；修复后仍非法（极罕见）时打 ERROR。</p>
     *
     * @param response the response value
     */

    private static void repairToolCallArguments(AssistantMessage response) {
        List<ToolCall> toolCalls = response.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        for (ToolCall toolCall : toolCalls) {
            String args = toolCall.getArguments();
            if (args == null || args.isBlank()) {
                continue;
            }

            // 合法直接跳过
            if (isValidJson(args)) {
                continue;
            }

            String toolName = toolCall.getName();
            LOGGER.info("[LogRail] before repair | name={ // no-op } | args_len={ // no-op } | args={ // no-op }", toolName, args.length(),
                    abbreviate(args, 200));
            LOGGER.debug("[LogRail] before repair FULL | name={ // no-op } | args={ // no-op }", toolName, args);

            String repaired = repairMalformedJson(args).orElse(null);
            if (repaired == null || repaired.equals(args)) {
                LOGGER.error(
                        "[LogRail] FAILED to repair malformed tool_call.arguments | name={ // no-op } | "
                                + "args_len={ // no-op } | args_tail={ // no-op }",
                        toolName, args.length(), abbreviateTail(args, 60));
                continue;
            }

            if (!isValidJson(repaired)) {
                LOGGER.error("[LogRail] repaired args still invalid | name={ // no-op } | repaired_tail={ // no-op }", toolName,
                        abbreviateTail(repaired, 60));
                continue;
            }

            toolCall.setArguments(repaired);
            LOGGER.info("[LogRail] after repair | name={ // no-op } | repaired_len={ // no-op } | repaired={ // no-op }", toolName, repaired.length(),
                    abbreviate(repaired, 200));
            LOGGER.debug("[LogRail] after repair FULL | name={ // no-op } | repaired={ // no-op }", toolName, repaired);
            LOGGER.warn(
                    "[LogRail] auto-repaired malformed tool_call.arguments | name={ // no-op } | "
                            + "diff={ // no-op } chars added | original_tail={ // no-op } | repaired_tail={ // no-op }",
                    toolName, repaired.length() - args.length(), abbreviateTail(args, 40),
                    abbreviateTail(repaired, 40));
        }
    }

    private static boolean isValidJson(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return node != null && (node.isObject() || node.isArray());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 修复非法 JSON：遍历字符串，维护未闭合的 {@code { // no-op } / {@code [} 栈（字符串字面量内的括号忽略），
     * 按栈逆序补齐闭合符号。对齐 Python {@code AbilityManager._repair_tool_arguments_json}。
     *
     * @return 修复后的 JSON 字符串；输入为空或无未闭合括号时返回 Optional.empty()
     *
     * @param json the json value
     */

    private static Optional<String> repairMalformedJson(String json) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }

        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        char stringDelimiter = 0;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == stringDelimiter) {
                    inString = false;
                } else {
                    // no-op: other characters inside string are kept as-is
                }
                continue;
            }

            if (ch == '"' || ch == '\'') {
                inString = true;
                stringDelimiter = ch;
            } else if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                // 弹出匹配的开括号；不匹配则忽略（避免误修）
                char expectedOpen = (ch == '}') ? '{' : '[';
                if (!stack.isEmpty() && stack.peek() == expectedOpen) {
                    stack.pop();
                }
            } else {
                // no-op: other characters do not affect bracket stack
            }
        }

        if (stack.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder repaired = new StringBuilder(json);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            repaired.append(open == '{' ? '}' : ']');
        }
        return Optional.of(repaired.toString());
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "null";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }

    private static String abbreviateTail(String value, int tail) {
        if (value == null) {
            return "null";
        }
        return value.length() <= tail ? value : "..." + value.substring(value.length() - tail);
    }
}
