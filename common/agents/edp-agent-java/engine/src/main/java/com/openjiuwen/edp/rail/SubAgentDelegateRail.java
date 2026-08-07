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

import com.openjiuwen.edp.tools.CallSubAgentTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent 委派 Rail。
 *
 * <p>拦截 LLM 对 {@code call_subagent} 工具的调用，用 {@code subagent_name} 作为 {@code agentName}
 * 构造 {@code _interrupt_kind=a2a_delegate} 中断，交由框架的
 * {@code RemoteInvocationBatchCoordinator} 根据 {@code remote-agents} 配置自动查找 URL 并行调度。</p>
 *
 * <p>精简版：不含归一化/熔断/脱敏/history_info，远端结果直接 {@code reject} 回喂 LLM。</p>
 *
 * @since 2026-07-27
 */
public class SubAgentDelegateRail extends BaseInterruptRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubAgentDelegateRail.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 构造子 Agent 委派 Rail。
     */
    public SubAgentDelegateRail() {
        super(List.of(CallSubAgentTool.TOOL_NAME));
        setPriority(88);
        LOGGER.info("SubAgentDelegateRail initialized, intercepting tool={}", CallSubAgentTool.TOOL_NAME);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        // 续传恢复：框架已通过 a2a_delegate 拿到远端结果，直接 reject 回喂
        if (resumeInput != null) {
            LOGGER.info("SubAgentDelegateRail: resume input received, rejecting as tool result");
            return reject(resumeInput);
        }

        Map<String, Object> args = parseToolArgs(toolCall);
        String subagentName = asString(args.get("subagent_name"));
        String query = asString(args.get("query"));

        LOGGER.info("[SubAgentDelegateRail] delegating to sub-agent, agentName='{}', queryLen={}",
                subagentName, query.length());

        // 直接用 subagent_name 作为 agentName，框架根据 remote-agents 配置自动查找 URL
        InterruptRequest request = InterruptRequest.builder()
                .message(query)
                .context(Map.of(
                        "agentName", subagentName,
                        "_interrupt_kind", "a2a_delegate"))
                .build();
        return interrupt(request);
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static Map<String, Object> parseToolArgs(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Map.of();
        }
        String arguments = toolCall.getArguments();
        try {
            return OBJECT_MAPPER.readValue(arguments, MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.warn("[SubAgentDelegateRail] failed to parse tool arguments, raw={}", arguments, e);
            return Map.of();
        }
    }
}
