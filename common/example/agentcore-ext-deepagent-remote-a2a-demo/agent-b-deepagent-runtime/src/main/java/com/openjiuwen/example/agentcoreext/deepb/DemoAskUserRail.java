/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepb;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Demo ask-user rail that feeds resume input back into {@code AskUserTool}.
 * <p>
 * When the LLM calls {@code ask_user} to pose a question, the question text is
 * carried in the tool call's {@code query} argument. This rail surfaces that
 * text as the interrupt message so downstream callers (and the CLI) see the
 * actual question instead of the bare tool name {@code "ask_user"}.
 *
 * @since 2026-07-03
 */
class DemoAskUserRail extends BaseInterruptRail {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final String DEFAULT_QUESTION = "请补充更多信息以便继续。";

    DemoAskUserRail() {
        super(List.of("ask_user"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        if (userInput != null) {
            return approve("{\"response\":\"" + escapeJson(String.valueOf(userInput)) + "\"}");
        }
        return interrupt(InterruptRequest.builder()
                .message(extractQuestion(toolCall))
                .build());
    }

    /**
     * Extracts the {@code query} argument the LLM passed to {@code ask_user},
     * falling back to a generic prompt when the argument is missing or
     * unparseable.
     *
     * @param toolCall the ask_user tool call carrying the question
     * @return the question text to present to the user
     */
    private static String extractQuestion(ToolCall toolCall) {
        if (toolCall != null) {
            try {
                Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
                if (args != null) {
                    Object query = args.get("query");
                    if (query instanceof String s && !s.isBlank()) {
                        return s.trim();
                    }
                }
            } catch (JsonSyntaxException | NullPointerException ignored) {
                // Malformed arguments fall through to the deterministic default.
            }
        }
        return DEFAULT_QUESTION;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
