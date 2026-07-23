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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * EDPAgent 增强 ask_user 工具。
 *
 * @since 2024-01-01
  *
 */

public final class EnhancedAskUserTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedAskUserTool.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EnhancedAskUserTool() {
    }

    /**
     * Builds the tool instance.
     */
    public static Tool build() {
        return EdpaBusinessTools.localTool(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, "向用户追问缺失信息，并支持话术模板参数。",
                EdpaBusinessTools.objectSchema(Map.of("question", EdpaBusinessTools.stringProp("需要向用户追问的问题"),
                        "response_template_keys", EdpaBusinessTools.arrayProp("响应话术模板 key 列表"),
                        "response_template_status", EdpaBusinessTools.stringProp("响应话术状态"), "response_template_vars",
                        EdpaBusinessTools.objectProp("响应话术变量"), "missing_fields",
                        EdpaBusinessTools.arrayProp("缺失字段列表")), List.of("question")),
                EnhancedAskUserTool::interruptForUserInput);
    }

    private static Object interruptForUserInput(Map<String, Object> inputs) {
        // 对齐 Python ask_user.py L17-22: [ask_user] question/status/keys/vars 全参数
        LOGGER.info("[ask_user] interrupting ask_user, question={}, status={}, keys={}, vars={}",
                abbreviate(String.valueOf(inputs.getOrDefault("question", "")), 200),
                abbreviate(String.valueOf(inputs.getOrDefault("response_template_status", "")), 40),
                inputs.get("response_template_keys"),
                abbreviate(String.valueOf(inputs.getOrDefault("response_template_vars", "")), 200));
        String question = String.valueOf(inputs.getOrDefault("question", "需要您确认以下信息"));
        InterruptRequest request = InterruptRequest.builder().interruptId("ask_user_interrupt").message(question)
                .context(Map.of("tool", EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, "inputs", inputs))
                .payloadSchema(EdpaBusinessTools.objectSchema(Map.of("answer", EdpaBusinessTools.stringProp("用户补充信息")),
                        List.of("answer")))
                .build();
        ToolCall toolCall = ToolCall.builder().id("ask_user_interrupt").name(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER)
                .arguments(toJson(inputs)).build();
        LOGGER.info("[ask_user] throwing ToolInterruptException, toolCallId={}, message={}", "ask_user_interrupt",
                question);
        throw new ToolInterruptException(request, toolCall);
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            LOGGER.error("[ask_user] JSON serialization failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize ask_user arguments", e);
        }
    }

    /**
     * 截断字符串到指定长度，用于日志输出避免过长。
     */
    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private static String abbreviate(String text) {
        return abbreviate(text, 100);
    }
}
