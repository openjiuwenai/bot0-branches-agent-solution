/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interrupt rail that delegates selected tool calls to remote A2A agents.
 *
 * @since 2026-06-30
 */
public class RemoteA2aInterruptRail extends BaseInterruptRail {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<String, RemoteA2aToolInstaller.RemoteA2aToolSpec> specsByToolName;

    public RemoteA2aInterruptRail(List<RemoteA2aToolInstaller.RemoteA2aToolSpec> specs) {
        super(specs.stream().map(RemoteA2aToolInstaller.RemoteA2aToolSpec::toolName).toList());
        this.specsByToolName = specs.stream().collect(Collectors.toUnmodifiableMap(
                RemoteA2aToolInstaller.RemoteA2aToolSpec::toolName,
                Function.identity()));
        specs.forEach(spec -> getTools().add(toToolCard(spec)));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            return reject(resumeInput);
        }
        String toolName = resolveToolName(ctx, toolCall);
        RemoteA2aToolInstaller.RemoteA2aToolSpec spec = Objects.requireNonNull(
                specsByToolName.get(toolName), "Unknown remote A2A tool: " + toolName);
        String message = extractMessage(toolCall);
        InterruptRequest request = InterruptRequest.builder()
                .message(message)
                .context(Map.of(
                        "agentName", spec.remoteAgentId(),
                        "_interrupt_kind", "a2a_delegate"))
                .build();
        return interrupt(request);
    }

    private static String resolveToolName(AgentCallbackContext ctx, ToolCall toolCall) {
        if (ctx != null && ctx.getInputs() instanceof ToolCallInputs inputs && inputs.getToolName() != null) {
            return inputs.getToolName();
        }
        return toolCall != null ? toolCall.getName() : "";
    }

    private static ToolCard toToolCard(RemoteA2aToolInstaller.RemoteA2aToolSpec spec) {
        return ToolCard.builder()
                .id(spec.toolName())
                .name(spec.toolName())
                .description(spec.description())
                .inputParams(spec.inputSchema())
                .build();
    }

    private static String extractMessage(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return "";
        }
        String arguments = toolCall.getArguments();
        try {
            Map<String, Object> args = OBJECT_MAPPER.readValue(arguments, MAP_TYPE);
            Object remoteInput = args.get("remoteInput");
            if (remoteInput instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (JsonProcessingException ignored) {
            // Fall back to raw tool arguments below.
        }
        return arguments;
    }
}
