/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.model.FinishAction;
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
import java.util.Optional;

/**
 * Resolves the virtual intent Tool and routes the same ToolCall to its selected target.
 *
 * <p>
 * A decision carrying {@link InvokeToolAction} rewrites the
 * ToolCall in place, so the target Tool runs under the caller's own loop. Any other decision is
 * answered by this Rail alone: the encoded result becomes the Tool message and the downstream Tool
 * is skipped.
 *
 * <p>
 * A decision carrying {@link FinishAction} additionally ends the calling Agent turn. The Tool
 * message is still written, but the Agent stops instead of taking another model turn, and
 * {@code output} is delivered as the answer. Whatever the Agent had planned after this Tool call is
 * abandoned, which is why only a result function that owns the final wording returns that action.
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
        String sessionId = sessionId(context);
        log.info("Intent Tool call intercepted sessionId={} toolCallId={} catalogVersion={}", sessionId, toolCallId,
                suite.snapshot().version());
        Map<String, Object> toolInputs;
        try {
            toolInputs = parseToolInputs(inputs.getToolArgs(), toolCall == null ? null : toolCall.getArguments());
        } catch (IllegalArgumentException exception) {
            log.warn("Intent Tool call rejected sessionId={} toolCallId={} reason={}", sessionId, toolCallId,
                    exception.getMessage());
            writeResult(context, inputs, failed("intent call inputs are invalid"), sessionId);
            return;
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        if (context.getSession() != null) {
            kwargs.put(SESSION_KWARG, context.getSession());
        }
        if (!sessionId.isEmpty()) {
            kwargs.put(IntentSuite.SESSION_ID_KWARG, sessionId);
        }
        if (context.getContext() != null) {
            kwargs.put(CONTEXT_KWARG, context.getContext());
        }
        IntentDecision decision = suite.resolve(toolInputs, kwargs);
        log.info("Intent Tool decision sessionId={} toolCallId={} status={} intentId={} actionType={}", sessionId,
                toolCallId, decision.status(), decision.intentId(),
                decision.action() == null ? "none" : decision.action().getClass().getSimpleName());
        if (decision.action() instanceof InvokeToolAction invokeAction) {
            routeInvokeAction(context, inputs, decision, invokeAction, sessionId);
            return;
        }
        writeResult(context, inputs, decision, sessionId);
    }

    /**
     * A remote delegate reports transport and business failures as an explicit
     * {@code {ok:false}} envelope. Convert that envelope into a terminal,
     * user-facing business-failure answer before the model gets another turn.
     * The caller task has handled the failure, so this is an answer rather than
     * an unhandled execution exception; the machine-readable fields preserve the
     * distinction between business failure and agent-task failure.
     *
     * @param context callback context after tool execution
     */
    @Override
    public void afterToolCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ToolCallInputs inputs)
                || !A2ADelegateIntentResultFunction.TOOL_NAME.equals(inputs.getToolName())) {
            return;
        }
        Optional<Map<?, ?>> failure = failureEnvelope(inputs.getToolResult());
        if (failure.isEmpty() || !Boolean.FALSE.equals(failure.get().get("ok"))) {
            return;
        }
        Map<?, ?> result = failure.get();
        String code = result.get("code") == null ? "REMOTE_FAILED" : String.valueOf(result.get("code"));
        String detail = result.get("message") == null ? "" : String.valueOf(result.get("message"));
        String message = friendlyFailureMessage(code, detail);
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("output", message);
        terminal.put("result_type", "answer");
        terminal.put("ok", false);
        terminal.put("businessSuccess", false);
        terminal.put("code", code);
        terminal.put("message", message);
        terminal.put("detail", detail);
        terminal.put("retryable", isRetryable(result));
        if (result.get("remoteAgentId") != null) {
            terminal.put("remoteAgentId", result.get("remoteAgentId"));
        }
        context.requestForceFinish(terminal);
    }

    private static String friendlyFailureMessage(String code, String detail) {
        return switch (code) {
            case "REMOTE_UNAVAILABLE" -> "当前服务暂时不可用，请稍后重试。";
            case "REMOTE_TIMEOUT" -> "服务响应超时，请稍后重试。";
            case "REMOTE_RATE_LIMITED", "REMOTE_OVERLOADED" -> "当前服务繁忙，请稍后重试。";
            case "REMOTE_BUSINESS_FAILURE" -> detail.isBlank() ? "业务处理未成功，请核对信息后重试。" : detail;
            default -> "远端业务处理未成功，请稍后重试。";
        };
    }

    private static boolean isRetryable(Map<?, ?> result) {
        if (result.get("retryable") instanceof Boolean retryable) {
            return retryable;
        }
        return result.get("remoteError") instanceof Map<?, ?> remoteError
                && Boolean.TRUE.equals(remoteError.get("retryable"));
    }

    private static Optional<Map<?, ?>> failureEnvelope(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Optional.of(map);
        }
        if (!(value instanceof String json) || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private static String sessionId(AgentCallbackContext context) {
        if (context.getSession() == null || context.getSession().getSessionId() == null) {
            return "";
        }
        return context.getSession().getSessionId();
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
            InvokeToolAction action, String sessionId) {
        if (TOOL_NAME.equals(action.toolName())) {
            writeResult(context, inputs,
                    failed(decision.intentId(), "intent action must not call the intent Tool again"), sessionId);
            return;
        }
        if (!A2ADelegateIntentResultFunction.TOOL_NAME.equals(action.toolName())
                && !isRegisteredAbility(context, action.toolName())) {
            writeResult(context, inputs,
                    failed(decision.intentId(), "target Tool is not registered: " + action.toolName()), sessionId);
            return;
        }
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall == null) {
            writeResult(context, inputs, failed(decision.intentId(), "intent call has no ToolCall"), sessionId);
            return;
        }
        String arguments;
        try {
            arguments = OBJECT_MAPPER.writeValueAsString(action.arguments());
        } catch (JsonProcessingException exception) {
            writeResult(context, inputs, failed(decision.intentId(), "target Tool arguments are not serializable"),
                    sessionId);
            return;
        }
        toolCall.setName(action.toolName());
        toolCall.setArguments(arguments);
        inputs.setToolName(action.toolName());
        inputs.setToolArgs(action.arguments());
        log.info("Intent Tool call routed sessionId={} intentId={} targetTool={} toolCallId={}", sessionId,
                decision.intentId(), action.toolName(), toolCall.getId());
    }

    private static boolean isRegisteredAbility(AgentCallbackContext context, String toolName) {
        return context.getAgent() instanceof BaseAgent agent && agent.getAbilityManager().get(toolName) != null;
    }

    private void writeResult(AgentCallbackContext context, ToolCallInputs inputs, IntentDecision decision,
            String sessionId) {
        IntentDecision encodable = decision;
        String encoded;
        try {
            encoded = resultCodec.encode(encodable);
        } catch (IllegalArgumentException exception) {
            encodable = failed(decision.intentId(), "intent result is not JSON serializable");
            encoded = resultCodec.encode(encodable);
        }
        String toolCallId = inputs.getToolCall() == null ? null : inputs.getToolCall().getId();
        inputs.setToolResult(encoded);
        inputs.setToolMsg(ToolMessage.builder().content(encoded).toolCallId(toolCallId).name(TOOL_NAME).build());
        context.getExtra().put("_skip_tool", Boolean.TRUE);
        log.info("Intent Tool call completed without downstream Tool sessionId={} toolCallId={} status={} intentId={}",
                sessionId, toolCallId, encodable.status(), encodable.intentId());
        if (encodable.action() instanceof FinishAction finishAction) {
            Map<String, Object> finishResult = new LinkedHashMap<>();
            finishResult.put("output", finishAction.output());
            finishResult.put("result_type", "answer");
            context.requestForceFinish(finishResult);
            log.info("Intent Tool call ended the Agent turn sessionId={} toolCallId={} intentId={}", sessionId,
                    toolCallId, encodable.intentId());
        }
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
