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

package com.huawei.ascend.edp.config;

/**
 * 工具名常量集中地（与框架 Core 工具名对齐）。
 *
 * <p>所有 Rail 引用工具名时必须使用此常量类，禁止硬编码字符串字面量。</p>
 *
 * @since 2024-01-01
 */

public final class ToolConstants {
    /** Core TaskPlanningRail 提供的 Todo 工具。 */
    public static final String TODO_CREATE = "todo_create";

    /** modify tool name. */
    public static final String TODO_MODIFY = "todo_modify";

    /** list tool name. */
    public static final String TODO_LIST = "todo_list";

    /** get tool name. */
    public static final String TODO_GET = "todo_get";

    /** 业务工具。 */
    public static final String CALL_MCP = "call_mcp";

    /** Call versatile tool name. */
    public static final String CALL_VERSATILE = "call_versatile";

    /** Ask user tool name. */
    public static final String ASK_USER = "ask_user";

    /** Cancel task tool name. */
    public static final String CANCEL_TASK = "cancel_task";

    /** 脚本执行工具。 */
    public static final String BASH = "bash";

    /** Skill tool name. */
    public static final String SKILL_TOOL = "skill_tool";

    private ToolConstants() {
    }
}
