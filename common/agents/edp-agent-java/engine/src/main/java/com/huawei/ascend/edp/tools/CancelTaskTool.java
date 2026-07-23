/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
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

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * EDPAgent 任务取消工具。
 *
 * @since 2024-01-01
 */
public final class CancelTaskTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(CancelTaskTool.class);

    private CancelTaskTool() {
    }

    /** Builds the tool instance. */
    public static Tool build() {
        // 对齐 Python cancel_task.py L26: [cancel_task] reason=...
        LOGGER.info("[cancel_task] building CancelTask tool: {}", EdpaBusinessTools.TOOL_CANCEL_TASK);
        return EdpaBusinessTools.localTool(EdpaBusinessTools.TOOL_CANCEL_TASK,
                "取消当前任务并触发取消清理话术。使用前必须先通过 ask_user 向用户确认取消意图，" + "获得用户明确确认后再调用此工具。",
                EdpaBusinessTools.objectSchema(Map.of("reason", EdpaBusinessTools.stringProp("取消原因")), List.of()),
                inputs -> Map.of("tool", EdpaBusinessTools.TOOL_CANCEL_TASK, "status", "cancelled", "input", inputs));
    }
}
