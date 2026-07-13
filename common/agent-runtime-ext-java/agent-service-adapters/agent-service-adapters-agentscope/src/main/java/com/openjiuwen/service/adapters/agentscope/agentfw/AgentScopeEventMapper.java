/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import com.openjiuwen.service.spec.dto.QueryChunk;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class AgentScopeEventMapper {
    Optional<QueryChunk> map(AgentEvent event, Supplier<AgentState> stateSupplier, StreamState streamState) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return Optional.of(new QueryChunk(
                QueryChunk.TYPE_CHUNK,
                Map.of("type", "answer_delta", "content", delta.getDelta())));
        }
        if (event instanceof RequireUserConfirmEvent confirmation) {
            validateConfirmationPending(confirmation.getToolCalls(), stateSupplier.get());
            streamState.confirmationEmitted = true;
            return Optional.of(interrupt(
                "confirmation",
                "The following operation requires confirmation.",
                confirmation.getToolCalls()));
        }
        if (event instanceof AgentResultEvent result && result.getResult() != null) {
            GenerateReason reason = result.getResult().getGenerateReason();
            if (reason == GenerateReason.TOOL_SUSPENDED) {
                return Optional.of(new QueryChunk(QueryChunk.TYPE_INTERRUPT, externalFromState(stateSupplier.get())));
            }
            if (reason == GenerateReason.PERMISSION_ASKING) {
                if (streamState.confirmationEmitted) {
                    return Optional.empty();
                }
                streamState.confirmationEmitted = true;
                return Optional.of(new QueryChunk(
                    QueryChunk.TYPE_INTERRUPT,
                    confirmationFromState(stateSupplier.get())));
            }
            if (isMessagePause(reason) && !streamState.messageEmitted) {
                streamState.messageEmitted = true;
                return Optional.of(interrupt("message", pauseMessage(result.getResult()), List.of()));
            }
            if (reason == GenerateReason.INTERRUPTED) {
                throw new CancellationException("AgentScope execution interrupted");
            }
            if (reason == GenerateReason.TOOL_CALLS) {
                throw new IllegalStateException(
                    "AgentScope returned TOOL_CALLS as a terminal result without a supported pause state");
            }
            return Optional.empty();
        }
        if (event instanceof RequestStopEvent stop) {
            if (stop.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                if (streamState.confirmationEmitted) {
                    return Optional.empty();
                }
                streamState.confirmationEmitted = true;
                return Optional.of(new QueryChunk(
                    QueryChunk.TYPE_INTERRUPT,
                    confirmationFromState(stateSupplier.get())));
            }
            if (!isMessagePause(stop.getGenerateReason())) {
                return Optional.empty();
            }
            String message = stop.getReason() == null || stop.getReason().isBlank()
                ? "Agent execution paused"
                : stop.getReason();
            streamState.messageEmitted = true;
            return Optional.of(interrupt("message", message, List.of()));
        }
        return Optional.empty();
    }

    Map<String, Object> mapResult(Msg result, AgentState state) {
        if (result == null) {
            throw new IllegalArgumentException("AgentScope result must not be null");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("role", "assistant");
        mapped.put("content", result.getTextContent() == null ? "" : result.getTextContent());
        GenerateReason reason = result.getGenerateReason();
        if (reason == null) {
            return mapped;
        }
        switch (reason) {
            case PERMISSION_ASKING -> mapped.put("_interrupt", confirmationFromState(state));
            case TOOL_SUSPENDED -> mapped.put("_interrupt", externalFromState(state));
            case MIDDLEWARE_STOP_REQUESTED, REASONING_STOP_REQUESTED, ACTING_STOP_REQUESTED ->
                mapped.put("_interrupt", interaction(
                    "message",
                    mapped.get("content") instanceof String text && !text.isBlank()
                        ? text
                        : "Agent execution paused",
                    List.of()));
            case INTERRUPTED -> throw new CancellationException("AgentScope execution interrupted");
            case MODEL_STOP, STRUCTURED_OUTPUT, MAX_ITERATIONS, ALL_TOOLS_DENIED -> {
                // Normal terminal results.
            }
            case TOOL_CALLS -> throw new IllegalStateException(
                "AgentScope returned TOOL_CALLS as a terminal result without a supported pause state");
            default -> throw new IllegalStateException("Unsupported AgentScope generate reason: " + reason);
        }
        return mapped;
    }

    private static Map<String, Object> confirmationFromState(AgentState state) {
        List<ToolUseBlock> asking = askingToolCalls(state);
        if (asking.isEmpty()) {
            throw new IllegalStateException("PERMISSION_ASKING result has no ASKING pending tool calls");
        }
        return interaction("confirmation", "The following operation requires confirmation.", asking);
    }

    private static List<ToolUseBlock> askingToolCalls(AgentState state) {
        return AgentScopeResumeMapper.pendingToolCalls(state).stream()
            .filter(tool -> tool.getState() == ToolCallState.ASKING)
            .toList();
    }

    private static Map<String, Object> externalFromState(AgentState state) {
        List<ToolUseBlock> external = AgentScopeResumeMapper.pendingToolCalls(state).stream()
            .filter(tool -> tool.getState() != ToolCallState.ASKING)
            .toList();
        if (external.size() != 1) {
            throw new IllegalStateException(
                "TOOL_SUSPENDED result must have exactly one external pending tool call");
        }
        return interaction("tool_result", "The following tool call requires an external result.", external);
    }

    private static QueryChunk interrupt(String kind, String message, List<ToolUseBlock> tools) {
        return new QueryChunk(QueryChunk.TYPE_INTERRUPT, interaction(kind, message, tools));
    }

    private static boolean isMessagePause(GenerateReason reason) {
        return reason == GenerateReason.MIDDLEWARE_STOP_REQUESTED
            || reason == GenerateReason.REASONING_STOP_REQUESTED
            || reason == GenerateReason.ACTING_STOP_REQUESTED;
    }

    private static String pauseMessage(Msg result) {
        String text = result.getTextContent();
        return text == null || text.isBlank() ? "Agent execution paused" : text;
    }

    private static Map<String, Object> interaction(String kind, String message, List<ToolUseBlock> tools) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolUseBlock tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "tool_call");
            item.put("name", tool.getName());
            item.put("arguments", tool.getInput());
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("items", items);
        Map<String, Object> interaction = new LinkedHashMap<>();
        interaction.put("type", "__interaction__");
        interaction.put("index", 0);
        interaction.put("payload", payload);
        interaction.put("message", message);
        interaction.put("context", Map.of("_interrupt_kind", "ask_user"));
        return interaction;
    }

    private static void validateConfirmationPending(List<ToolUseBlock> eventTools, AgentState state) {
        Set<String> eventIds = eventTools.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());
        Set<String> askingIds = askingToolCalls(state).stream()
            .map(ToolUseBlock::getId)
            .collect(Collectors.toSet());
        if (eventIds.isEmpty() || !eventIds.equals(askingIds)) {
            throw new IllegalStateException(
                "RequireUserConfirmEvent does not match current ASKING pending tool calls");
        }
    }

    static final class StreamState {
        private boolean confirmationEmitted;
        private boolean messageEmitted;
    }
}
