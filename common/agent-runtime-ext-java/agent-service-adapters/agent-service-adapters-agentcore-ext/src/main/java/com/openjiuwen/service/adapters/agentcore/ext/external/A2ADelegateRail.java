/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.result.A2ADelegateIntentResultFunction;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Converts the internal A2A delegate target into the existing Runtime interrupt contract.
 *
 * @since 0.1.0
 */
public final class A2ADelegateRail extends BaseInterruptRail {
    /** Internal Tool target used for Runtime A2A delegation. */
    public static final String TARGET_NAME = A2ADelegateIntentResultFunction.TOOL_NAME;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(A2ADelegateRail.class);

    private final A2ARemoteAgentCardRegistry registry;

    /**
     * Creates an A2A delegate Rail backed by the remote Agent registry.
     *
     * @param registry
     *            remote Agent Card registry
     */
    public A2ADelegateRail(A2ARemoteAgentCardRegistry registry) {
        super(java.util.List.of(TARGET_NAME));
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext context, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            log.info("A2A delegate Rail rejected resumed invocation toolCallId={}", toolCallId(toolCall));
            return reject(resumeInput);
        }
        Map<String, Object> arguments;
        try {
            arguments = parseArguments(toolCall);
        } catch (IllegalArgumentException exception) {
            log.info("A2A delegate Rail rejected invalid arguments toolCallId={} reason={}", toolCallId(toolCall),
                    exception.getMessage());
            return reject(exception.getMessage());
        }
        String agentName = text(arguments.get("agentName"));
        String remoteInput = text(arguments.get("remoteInput"));
        if (agentName.isBlank() || remoteInput.isBlank()) {
            log.info("A2A delegate Rail rejected incomplete arguments toolCallId={} agentName={}",
                    toolCallId(toolCall), agentName);
            return reject("a2a_delegate requires non-blank agentName and remoteInput");
        }
        if (registry.get(agentName).isEmpty()) {
            log.info("A2A delegate Rail rejected unknown remote Agent toolCallId={} agentName={}",
                    toolCallId(toolCall), agentName);
            return reject("Remote A2A agent is not registered: " + agentName);
        }
        log.info("A2A delegate Rail created interrupt toolCallId={} agentName={} remoteInputLength={}",
                toolCallId(toolCall), agentName, remoteInput.length());
        return interrupt(InterruptRequest.builder().message(remoteInput)
                .context(Map.of("agentName", agentName, "_interrupt_kind", "a2a_delegate")).build());
    }

    private static Map<String, Object> parseArguments(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null || toolCall.getArguments().isBlank()) {
            throw new IllegalArgumentException("a2a_delegate arguments must be a JSON object");
        }
        try {
            return OBJECT_MAPPER.readValue(toolCall.getArguments(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("a2a_delegate arguments must be a JSON object", exception);
        }
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return "";
        }
        return text.trim();
    }

    private static String toolCallId(ToolCall toolCall) {
        return toolCall == null ? null : toolCall.getId();
    }
}
