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
 * EDPAgent Versatile Agent 委托工具。
 *
 * @since 2024-01-01
 *
 */

public final class CallVersatileTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallVersatileTool.class);

    private CallVersatileTool() {
    }

    /**
     * Builds the tool instance.
     *
     * @return the result
     */
    public static Tool build() {
        // 对齐 Python call_versatile.py L33-40: [call_versatile] intent=..., desc=...
        LOGGER.info("[call_versatile] building Versatile tool: {}", EdpaBusinessTools.TOOL_CALL_VERSATILE);
        return EdpaBusinessTools.localTool(EdpaBusinessTools.TOOL_CALL_VERSATILE,
                "声明 Versatile Agent 委托意图，级联恢复和结果归一化由 VersatileInterruptRail 负责。",
                EdpaBusinessTools.objectSchema(
                        Map.of("query_description", EdpaBusinessTools.stringProp("委托查询描述"), "query_intent",
                                EdpaBusinessTools.stringProp("委托查询意图"), "query_response_analysis_scripts",
                                EdpaBusinessTools.arrayProp("响应归一化脚本列表"), "response_template_keys",
                                EdpaBusinessTools.arrayProp("响应话术模板 key 列表"), "notice_context",
                                EdpaBusinessTools.objectProp("非中断话术上下文"), "input_key",
                                EdpaBusinessTools.stringProp("从 ToolDataChannel 读取前序数据的 key")),
                        List.of("query_description", "query_intent")),
                inputs -> Map.of("tool", EdpaBusinessTools.TOOL_CALL_VERSATILE, "status", "delegate_intent", "input",
                        inputs));
    }
}
