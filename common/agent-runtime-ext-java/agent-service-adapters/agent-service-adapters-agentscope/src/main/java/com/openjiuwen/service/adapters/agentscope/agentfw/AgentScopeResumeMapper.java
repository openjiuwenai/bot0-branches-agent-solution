/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import com.openjiuwen.service.spec.dto.ServeRequest;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts runtime resume requests into AgentScope confirmation and tool-result messages.
 *
 * @since 2026-07-20
 */
final class AgentScopeResumeMapper {
    private static final String INTERRUPT = "_interrupt";

    boolean hasResumeInteraction(ServeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Object interaction = request.getMetadata() == null ? null : request.getMetadata().get(INTERRUPT);
        return interaction instanceof Map<?, ?>;
    }

    List<Msg> map(ServeRequest request, AgentState state) {
        Objects.requireNonNull(request, "request must not be null");
        String interactionKind = resumeInteractionKind(request);
        if ("message".equals(interactionKind)) {
            return List.of();
        }
        List<ToolUseBlock> pending = pendingToolCalls(state);
        String content = AgentScopeRequestMapper.latestEffectiveContent(request);
        List<ToolUseBlock> asking = pending.stream()
            .filter(tool -> tool.getState() == ToolCallState.ASKING)
            .toList();
        if ("confirmation".equals(interactionKind)) {
            if (asking.isEmpty()) {
                throw new IllegalArgumentException("AgentScope has no pending confirmation to resume");
            }
            return mapConfirmation(content, asking);
        }
        if ("tool_result".equals(interactionKind)) {
            if (!asking.isEmpty()) {
                throw new IllegalArgumentException("AgentScope confirmation state does not match tool_result resume");
            }
            return mapExternalResult(content, pending);
        }
        throw new IllegalArgumentException("Unsupported A2A resume interaction kind: " + interactionKind);
    }

    private static String resumeInteractionKind(ServeRequest request) {
        Object raw = request.getMetadata() == null ? null : request.getMetadata().get(INTERRUPT);
        Object payload = raw instanceof Map<?, ?> interaction ? interaction.get("payload") : null;
        Object kind = payload instanceof Map<?, ?> data ? data.get("kind") : null;
        if (!(kind instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("A2A resume interaction kind is missing");
        }
        return value;
    }

    private static List<Msg> mapConfirmation(String content, List<ToolUseBlock> pending) {
        String action = content.trim().toUpperCase(Locale.ROOT);
        boolean approved;
        if ("APPROVE".equals(action)) {
            approved = true;
        } else if ("REJECT".equals(action)) {
            approved = false;
        } else {
            throw new IllegalArgumentException("ASKING tool responses must use APPROVE or REJECT");
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock tool : pending) {
            results.add(new ConfirmResult(approved, tool));
        }
        return List.of(UserMessage.builder()
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
            .build());
    }

    private static List<Msg> mapExternalResult(String content, List<ToolUseBlock> pending) {
        if (pending.size() != 1) {
            throw new IllegalArgumentException(
                "message-based resume requires exactly one external pending tool");
        }
        ToolUseBlock tool = pending.get(0);
        ToolResultBlock result = ToolResultBlock.of(
            tool.getId(), tool.getName(), TextBlock.builder().text(content).build());
        return List.of(Msg.builder().role(MsgRole.TOOL).content(result).build());
    }

    static List<ToolUseBlock> pendingToolCalls(AgentState state) {
        Objects.requireNonNull(state, "AgentScope state must not be null");
        List<Msg> context = state.getContext();
        Msg lastAssistant = null;
        for (int index = context.size() - 1; index >= 0; index--) {
            if (context.get(index).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = context.get(index);
                break;
            }
        }
        if (lastAssistant == null) {
            return List.of();
        }
        Set<String> resultIds = context.stream()
            .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
            .map(ToolResultBlock::getId)
            .collect(Collectors.toSet());
        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
            .filter(tool -> tool.getId() != null && !resultIds.contains(tool.getId()))
            .toList();
    }
}
