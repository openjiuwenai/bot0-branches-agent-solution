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

import com.huawei.ascend.edp.config.EdpConfig;

import com.openjiuwen.core.foundation.tool.Tool;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * EDPAgent 内置工具注册表。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>建立工具名称（YAML 中 allowed_tools 列表）到 Java 构建函数的映射。</li>
 *     <li>使 {@link EdpaBusinessTools#build} 可以按配置驱动注册，而非硬编码。</li>
 *     <li>场景级自定义工具可通过 SPI 扩展（未来）。</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *     <li>bash、skill_tool 是 DeepAgent 原生工具，不在此注册表中。</li>
 *     <li>todo_create / todo_modify / todo_list / todo_get 由 Core 框架 TaskPlanningRail 自动注册。</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public final class EdpaToolRegistry {
    private static final Map<String, Function<EdpConfig, Tool>> BUILTIN_TOOLS = Map.of(EdpaBusinessTools.TOOL_CALL_MCP,
            cfg -> CallMcpTool.build(), EdpaBusinessTools.TOOL_CALL_VERSATILE, cfg -> CallVersatileTool.build(),
            EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, cfg -> EnhancedAskUserTool.build(),
            EdpaBusinessTools.TOOL_CANCEL_TASK, cfg -> CancelTaskTool.build());

    private EdpaToolRegistry() {
    }

    /**
     * 根据工具名称构建工具实例。
     *
     * @param name 工具名称（如 "call_mcp"）
     * @param edpConfig EDP 专有配置
     * @return 工具实例，未知名称返回 Optional.empty()
     */

    public static Optional<Tool> build(String name, EdpConfig edpConfig) {
        Function<EdpConfig, Tool> builder = BUILTIN_TOOLS.get(name);
        return builder != null ? Optional.of(builder.apply(edpConfig)) : Optional.empty();
    }

    /**
     * 判断是否为已知内置工具。
     *
     * @param name 工具名称
     * @return true 如果此名称在注册表中
     */

    public static boolean isBuiltin(String name) {
        return BUILTIN_TOOLS.containsKey(name);
    }
}
