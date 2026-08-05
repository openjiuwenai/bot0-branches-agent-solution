/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.travel.mainplan.tools;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

import java.util.List;
import java.util.Map;

/**
 * Tool that signals the agent needs more information from the user.
 *
 * <p>The interrupt/resume logic lives in
 * {@link com.openjiuwen.example.travel.mainplan.rails.UserInputInterruptRail};
 * this tool is the LLM-callable trigger. It plays two roles across the lifecycle:
 * <ul>
 *   <li><b>LLM call (pre-interrupt):</b> the LLM invokes it with
 *       {@code missing_fields} + {@code follow_up_message} to flag missing info.
 *       The rail intercepts and interrupts <em>before</em> the tool runs, so those
 *       args are never executed/validated server-side — they only guide the LLM and
 *       feed the rail's follow-up message.</li>
 *   <li><b>Resume:</b> the rail approves with overridden args
 *       {@code {"response": <user answer>}} and the tool runs, returning the user's
 *       answer so the agent can act on it.</li>
 * </ul>
 *
 * <p>This mirrors the framework {@code AskUserTool} contract — the resumed tool
 * receives the answer under the {@code response} key and returns it. The input
 * schema is therefore intentionally permissive (no {@code required} fields,
 * {@code response} declared as a property): only the resume path actually executes
 * and validates, and it carries just {@code response}.
 *
 * @since 2026-07-09
 */
public class RequestUserInputTool extends LocalFunction {
    /** Tool identifier intercepted by the user-input rail. */
    public static final String TOOL_REQUEST_USER_INPUT = "request_user_input";

    /**
     * Create the request user input tool.
     */
    public RequestUserInputTool() {
        super(
                ToolCard.builder()
                        .id(TOOL_REQUEST_USER_INPUT)
                        .name(TOOL_REQUEST_USER_INPUT)
                        .description("【警告：禁止直接文本回复】当且仅当目的地、出发日期、差标任一缺失时，必须调用此工具。")
                        .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "missing_fields", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "缺失的字段列表，可选值：['目的地', '出发日期', '差标']"
                                ),
                                "follow_up_message", Map.of(
                                    "type", "string",
                                    "description", "向用户发起追问的自然语言消息。例如：'请提供本次出差的差标。'"
                                ),
                                "response", Map.of(
                                    "type", "string",
                                    "description", "【LLM严禁填写】用户在下一轮中提供的回答。大模型在发起追问时绝对不要填写此字段！"
                                )
                            ),
                            "required", List.of("follow_up_message")
                        ))
                        .build(),
                // Resume 契约（对齐框架 AskUserTool）：返回 UserInputInterruptRail.toJsonArgs
                // 注入到 "response" 键里的用户回答；无 response 时返回空串。
                (inputs) -> {
                    Object response = inputs == null ? null : inputs.get("response");
                    return response != null ? String.valueOf(response) : "";
                }
        );
    }
}
