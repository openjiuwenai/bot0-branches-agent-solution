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

package com.openjiuwen.edp.tools;

import com.openjiuwen.core.foundation.tool.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 通用子智能体委托工具。
 *
 * <p>与 {@link CallVersatileTool} 的差异：增加 {@code agent_name} 必填参数，
 * 由场景 Skill 配置指定目标子智能体，Rail 从参数读取而非硬编码。</p>
 *
 * @since 2024-01-01
 */

public final class CallSubagentTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallSubagentTool.class);

    private CallSubagentTool() {
    }

    /**
     * Builds the tool instance.
     *
     * @return the result
     */
    public static Tool build() {
        LOGGER.info("[call_subagent] building Subagent tool: {}", EdpaBusinessTools.TOOL_CALL_SUBAGENT);
        return EdpaBusinessTools.localTool(EdpaBusinessTools.TOOL_CALL_SUBAGENT,
                "声明子智能体委托意图，由 agent_name + query_description 驱动，SubagentDelegateRail 构造 a2a_delegate 中断，"
                        + "框架 A2AEnabledServeOrchestrator 接管远端调用与续传，结果归一化由 SubagentDelegateRail.afterToolCall 负责。"
                        + "query_response_analysis_scripts 为可选，传入则执行归一化脚本，未传入则跳过直接返回远端原始结果。",
                EdpaBusinessTools.objectSchema(
                        Map.of("agent_name", EdpaBusinessTools.stringProp("目标子智能体名称（从 Skill 配置读取）"),
                                "query_description", EdpaBusinessTools.stringProp("委托查询描述"),
                                "query_response_analysis_scripts",
                                EdpaBusinessTools.arrayProp("响应归一化脚本列表（可选，传入则执行归一化，未传入则跳过）"),
                                "response_template_keys",
                                EdpaBusinessTools.arrayProp("响应话术模板 key 列表"),
                                "notice_context",
                                EdpaBusinessTools.objectProp("非中断话术上下文"),
                                "input_key",
                                EdpaBusinessTools.stringProp("从 ToolDataChannel 读取前序数据的 key")),
                        List.of("agent_name", "query_description")),
                inputs -> Map.of("tool", EdpaBusinessTools.TOOL_CALL_SUBAGENT, "status", "delegate_intent", "input",
                        inputs));
    }
}
