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
 * 子 Agent 委派工具。
 *
 * <p>LLM 识别到 ≥2 个业务实体时，为每个实体分别调用此工具（同轮调用多个实现并行）。
 * 工具返回 {@code delegate_intent} 状态，实际远端调用由 {@link com.openjiuwen.edp.rail.SubAgentDelegateRail}
 * 拦截后构造 {@code a2a_delegate} 中断，交由框架 {@code RemoteInvocationBatchCoordinator} 并行执行。</p>
 *
 * @since 2026-07-27
 */
public final class CallSubAgentTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallSubAgentTool.class);

    /** 工具名。 */
    public static final String TOOL_NAME = "call_subagent";

    private CallSubAgentTool() {
    }

    /**
     * 构造工具实例。
     *
     * @return 工具实例
     */
    public static Tool build() {
        LOGGER.info("[call_subagent] building sub-agent delegation tool: {}", TOOL_NAME);
        return EdpaBusinessTools.localTool(TOOL_NAME,
                "委托子 Agent 处理单个实体的业务请求。当用户 Query 涉及多个业务实体时，"
                        + "为每个实体分别调用此工具（同轮调用多个实现并行）。",
                EdpaBusinessTools.objectSchema(
                        Map.of(
                                "subagent_name", EdpaBusinessTools.stringProp("子 Agent 名称（如 zhidaitong）"),
                                "query", EdpaBusinessTools.stringProp("该实体的业务请求描述")),
                        List.of("subagent_name", "query")),
                inputs -> Map.of("tool", TOOL_NAME, "status", "delegate_intent", "input", inputs));
    }
}
