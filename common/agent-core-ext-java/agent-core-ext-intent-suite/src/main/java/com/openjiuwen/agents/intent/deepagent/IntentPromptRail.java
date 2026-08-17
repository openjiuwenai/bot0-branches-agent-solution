/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the intent Tool visible and merges routing instructions into the request's
 * first system message.
 *
 * @since 0.1.0
 */
public final class IntentPromptRail extends AgentRail {
    /** Name used to identify the request-only intent prompt. */
    public static final String PROMPT_NAME = "intent-routing";

    /** Runs after the high-priority Tool filtering Rails. */
    public static final int PRIORITY = 10;

    private final ToolInfo intentTool;
    private final String routingInstructions;

    /**
     * Creates the prompt Rail.
     *
     * @param intentToolCard intent Tool metadata
     * @param routingInstructions request-only system instructions
     */
    public IntentPromptRail(ToolCard intentToolCard, String routingInstructions) {
        intentTool = Objects.requireNonNull(intentToolCard, "intentToolCard").toolInfo();
        if (routingInstructions == null || routingInstructions.isBlank()) {
            throw new IllegalArgumentException("routingInstructions must not be blank");
        }
        this.routingInstructions = routingInstructions.strip();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void beforeModelCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        List<ToolInfo> tools = mergeIntentTool(inputs.getTools());
        inputs.setTools(tools);
        if (!containsTool(tools, IntentRoutingRail.TOOL_NAME)) {
            return;
        }
        List<Object> messages = inputs.getMessages() == null
                ? new ArrayList<>()
                : new ArrayList<>(inputs.getMessages());
        mergeRoutingPrompt(messages);
        inputs.setMessages(messages);
    }

    private List<ToolInfo> mergeIntentTool(List<ToolInfo> current) {
        Map<String, ToolInfo> byName = new LinkedHashMap<>();
        if (current != null) {
            current.stream().filter(Objects::nonNull).forEach(tool -> byName.putIfAbsent(tool.getName(), tool));
        }
        byName.putIfAbsent(intentTool.getName(), intentTool);
        return new ArrayList<>(byName.values());
    }

    private static boolean containsTool(List<ToolInfo> tools, String name) {
        return tools.stream().anyMatch(tool -> name.equals(tool.getName()));
    }

    private void mergeRoutingPrompt(List<Object> messages) {
        for (int index = 0; index < messages.size(); index++) {
            Object value = messages.get(index);
            if (!(value instanceof SystemMessage systemMessage)) {
                continue;
            }
            if (PROMPT_NAME.equals(systemMessage.getName())) {
                return;
            }
            String existing = systemMessage.getContentAsString();
            String merged = existing == null || existing.isBlank()
                    ? routingInstructions
                    : existing + "\n\n" + routingInstructions;
            messages.set(index, new SystemMessage(merged, PROMPT_NAME));
            return;
        }
        messages.add(0, new SystemMessage(routingInstructions, PROMPT_NAME));
    }
}
