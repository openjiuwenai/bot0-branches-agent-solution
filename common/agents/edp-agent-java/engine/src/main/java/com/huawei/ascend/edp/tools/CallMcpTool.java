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

package com.huawei.ascend.edp.tools;

import com.openjiuwen.core.foundation.tool.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * EDPAgent MCP 调用工具。
 *
 * @since 2024-01-01
 *
 */

public final class CallMcpTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallMcpTool.class);

    private CallMcpTool() {
    }

    /**
     * Builds the tool instance.
     *
     * @return the result
     */
    public static Tool build() {
        // 对齐 Python call_mcp.py L31-34: [call_mcp] script_command=..., script_params=...
        LOGGER.info("[call_mcp] building MCP tool: {}", EdpaBusinessTools.TOOL_CALL_MCP);
        return EdpaBusinessTools.localTool(EdpaBusinessTools.TOOL_CALL_MCP,
                "声明 MCP 沙箱脚本调用意图，执行和数据通道写入由 McpInterruptRail 负责。",
                EdpaBusinessTools.objectSchema(Map.of("script_command", EdpaBusinessTools.stringProp("待执行的 MCP 脚本或命令"),
                        "script_params", EdpaBusinessTools.objectProp("脚本入参")), List.of("script_command")),
                inputs -> Map.of("tool", EdpaBusinessTools.TOOL_CALL_MCP, "status", "mcp_intent", "input", inputs));
    }
}
