/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.prompt.DefaultIntentPrompt;
import com.openjiuwen.agents.intent.result.A2ADelegateIntentResultFunction;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the virtual intent Tool and routes the same ToolCall to its selected target.
 *
 * @since 0.1.0
 */
public final class IntentRoutingRail extends AgentRail {
    /** Model-visible intent Tool name. */
    public static final String TOOL_NAME = "intent_match";

    /** Tool kwargs key for the active session. */
    public static final String SESSION_KWARG = "session";

    /** Tool kwargs key for the active model context. */
    public static final String CONTEXT_KWARG = "context";

    /** Rail priority. Callback execution currently uses higher values first. */
    public static final int PRIORITY = 110;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(IntentRoutingRail.class);

    private final IntentSuite suite;
    private final IntentToolResultCodec resultCodec;

    /**
     * Creates a routing Rail and its virtual ToolCard.
     *
     * @param suite global intent suite
     */
    public IntentRoutingRail(IntentSuite suite) {
        this(suite, createToolCard(Objects.requireNonNull(suite, "suite")), new IntentToolResultCodec());
    }

    IntentRoutingRail(IntentSuite suite, ToolCard toolCard, IntentToolResultCodec resultCodec) {
        super(List.of(Objects.requireNonNull(toolCard, "toolCard")));
        this.suite = Objects.requireNonNull(suite, "suite");
        this.resultCodec = Objects.requireNonNull(resultCodec, "resultCodec");
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void beforeToolCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ToolCallInputs inputs) || !isIntentCall(inputs)) {
            return;
        }
        ToolCall toolCall = inputs.getToolCall();
        String toolCallId = toolCall == null ? null : toolCall.getId();
        log.info("Intent Tool call intercepted toolCallId={} catalogVersion={}", toolCallId,
                suite.snapshot().version());
        Map<String, Object> toolInputs;
        try {
            toolInputs = parseToolInputs(inputs.getToolArgs(), toolCall == null ? null : toolCall.getArguments());
        } catch (IllegalArgumentException exception) {
            log.info("Intent Tool call rejected toolCallId={} reason={}", toolCallId, exception.getMessage());
            writeResult(context, inputs, failed("意图调用输入无效"));
            return;
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        if (context.getSession() != null) {
            kwargs.put(SESSION_KWARG, context.getSession());
        }
        if (context.getContext() != null) {
            kwargs.put(CONTEXT_KWARG, context.getContext());
        }
        IntentDecision decision = suite.resolve(toolInputs, kwargs);
        log.info("Intent Tool decision toolCallId={} status={} intentId={} actionType={}", toolCallId,
                decision.status(), decision.intentId(),
                decision.action() == null ? "none" : decision.action().getClass().getSimpleName());
        if (decision.action() instanceof InvokeToolAction invokeAction) {
            routeInvokeAction(context, inputs, decision, invokeAction);
            return;
        }
        writeResult(context, inputs, decision);
    }

    /**
     * Returns the virtual ToolCard registered by this Rail.
     *
     * @return intent ToolCard
     */
    public ToolCard toolCard() {
        return getTools().get(0);
    }

    private static boolean isIntentCall(ToolCallInputs inputs) {
        if (TOOL_NAME.equals(inputs.getToolName())) {
            return true;
        }
        return inputs.getToolCall() != null && TOOL_NAME.equals(inputs.getToolCall().getName());
    }

    private static Map<String, Object> parseToolInputs(Object toolArgs, String toolCallArguments) {
        Object source = toolArgs;
        if (source == null) {
            source = toolCallArguments;
        }
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (!(source instanceof String json) || json.isBlank()) {
            throw new IllegalArgumentException("intent Tool arguments must be a JSON object");
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("intent Tool arguments must be a JSON object", exception);
        }
    }

    private void routeInvokeAction(AgentCallbackContext context, ToolCallInputs inputs, IntentDecision decision,
            InvokeToolAction action) {
        if (TOOL_NAME.equals(action.toolName())) {
            writeResult(context, inputs, failed(decision.intentId(), "意图动作不能再次调用意图工具"));
            return;
        }
        if (!A2ADelegateIntentResultFunction.TOOL_NAME.equals(action.toolName())
                && !isRegisteredAbility(context, action.toolName())) {
            writeResult(context, inputs, failed(decision.intentId(), "目标工具未注册: " + action.toolName()));
            return;
        }
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall == null) {
            writeResult(context, inputs, failed(decision.intentId(), "意图调用缺少 ToolCall"));
            return;
        }
        String arguments;
        try {
            arguments = OBJECT_MAPPER.writeValueAsString(action.arguments());
        } catch (JsonProcessingException exception) {
            writeResult(context, inputs, failed(decision.intentId(), "目标工具参数无法序列化"));
            return;
        }
        toolCall.setName(action.toolName());
        toolCall.setArguments(arguments);
        inputs.setToolName(action.toolName());
        inputs.setToolArgs(action.arguments());
        log.info("Intent Tool call routed intentId={} targetTool={} toolCallId={}", decision.intentId(),
                action.toolName(), toolCall.getId());
    }

    private static boolean isRegisteredAbility(AgentCallbackContext context, String toolName) {
        return context.getAgent() instanceof BaseAgent agent && agent.getAbilityManager().get(toolName) != null;
    }

    private void writeResult(AgentCallbackContext context, ToolCallInputs inputs, IntentDecision decision) {
        IntentDecision encodable = decision;
        String encoded;
        try {
            encoded = resultCodec.encode(encodable);
        } catch (IllegalArgumentException exception) {
            encodable = failed(decision.intentId(), "意图结果无法序列化");
            encoded = resultCodec.encode(encodable);
        }
        String toolCallId = inputs.getToolCall() == null ? null : inputs.getToolCall().getId();
        inputs.setToolResult(encoded);
        inputs.setToolMsg(ToolMessage.builder().content(encoded).toolCallId(toolCallId).name(TOOL_NAME).build());
        context.getExtra().put("_skip_tool", Boolean.TRUE);
        log.info("Intent Tool call completed without downstream Tool toolCallId={} status={} intentId={}", toolCallId,
                encodable.status(), encodable.intentId());
    }

    private static IntentDecision failed(String message) {
        return failed(null, message);
    }

    private static IntentDecision failed(String intentId, String message) {
        return new IntentDecision(IntentDecisionStatus.FAILED, intentId, null, message);
    }

    private static ToolCard createToolCard(IntentSuite suite) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("type", "string");
        semantic.put("description", "Complete user request or subtask derived from the latest context");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("semantic", semantic));
        schema.put("required", List.of("semantic"));
        schema.put("additionalProperties", false);
        return ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
                .description(DefaultIntentPrompt.toolDescription(suite.config().prompt())).inputParams(schema).build();
    }
}
