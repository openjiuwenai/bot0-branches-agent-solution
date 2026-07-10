/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot probe on first {@link #invoke} to detect tool-calling bypass.
 *
 * <p>Sends a hard-to-refuse probe prompt with a minimalist {@code __probe_tool__}
 * on the very first call. If the underlying
 * {@link com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient} returns a
 * response without tool_calls despite the forced prompt, the client is silently
 * discarding the tools parameter — a {@link ToolCallingBypassException} is thrown.
 *
 * <p>Construction is identical to {@link Model}: pass the same
 * {@link ModelClientConfig} and {@link ModelRequestConfig}. The real client is
 * created internally via the parent's private {@code createModelClient} path.
 *
 * <p>Drop-in replacement:
 * <pre>{@code
 *   // Before: Model m = new Model(cliCfg, reqCfg);
 *   Model m = new ToolCallingEnforcingModel(cliCfg, reqCfg);
 *   agent.setLlm(m);  // unchanged — polymorphism
 * }</pre>
 *
 * @since 2026-07
 */
public class ToolCallingEnforcingModel extends Model {
    private static final String PROBE_TOOL_NAME = "__probe_tool__";

    private final AtomicBoolean probeDone = new AtomicBoolean(false);

    /**
     * Constructs an enforcing model. The real {@code BaseModelClient} is created
     * internally via chaining to {@code super(config, requestConfig)}.
     *
     * @param config        client-level configuration (provider, key, endpoint, etc.)
     * @param requestConfig request-level configuration (model name, temperature, etc.)
     */
    public ToolCallingEnforcingModel(ModelClientConfig config, ModelRequestConfig requestConfig) {
        super(config, requestConfig);
    }

    /**
     * Overrides {@link Model#invoke} with a one-shot probe on the very first call.
     *
     * <p>If the probe response has no tool_calls despite a forced prompt, a
     * {@link ToolCallingBypassException} is thrown — the client silently discards tools.
     * Subsequent invocations bypass probing and delegate directly to
     * {@link Model#invoke}.
     *
     * @param messages chat messages passed to the model
     * @param tools tool definitions visible to the model
     * @param temperature sampling temperature override
     * @param maxTokens maximum token override
     * @param model model name override
     * @param n number of completions
     * @param stop stop sequence
     * @param parser output parser
     * @param topP nucleus sampling override
     * @param kwargs provider-specific request parameters
     * @return assistant message returned by the model
     * @throws Exception propagated from the underlying model invocation
     */
    @Override
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens, String model,
            Integer n, String stop, BaseOutputParser parser, Float topP, Map<String, Object> kwargs) throws Exception {
        String effectiveModel = effectiveModel(model);
        Map<String, Object> effectiveKwargs = withThinkingKwargs(kwargs);
        // Probe exactly once (thread-safe via AtomicBoolean.CAS).
        // If the probe throws, the real invoke never executes — fail-fast.
        if (probeDone.compareAndSet(false, true)) {
            ProbeRequest request = new ProbeRequest();
            request.temperature = temperature;
            request.maxTokens = maxTokens;
            request.model = effectiveModel;
            request.n = n;
            request.stop = stop;
            request.parser = parser;
            request.topP = topP;
            request.kwargs = effectiveKwargs;
            doProbe(request);
        }
        // Forward the real invocation.
        return super.invoke(messages, tools, temperature, maxTokens, effectiveModel, n, stop, parser, topP,
                effectiveKwargs);
    }

    private String effectiveModel(String model) {
        // ReActAgent passes null/empty for model; the client does not always fall back
        // to ModelRequestConfig.getModelName() from its own state.
        return model == null || model.isEmpty() ? getModelConfig().getModelName() : model;
    }

    private static Map<String, Object> withThinkingKwargs(Map<String, Object> kwargs) {
        // LLM_THINKING values:
        // thinking-on/off -> DeepSeek thinking, qwen-on/off -> Qwen thinking, none -> no injection.
        String thinkingMode = System.getenv("LLM_THINKING");
        if (thinkingMode == null || thinkingMode.isEmpty() || "none".equals(thinkingMode)) {
            return kwargs;
        }
        Map<String, Object> extendedKwargs = new HashMap<>();
        if (kwargs != null) {
            extendedKwargs.putAll(kwargs);
        }
        if ("thinking-on".equals(thinkingMode) || "thinking-off".equals(thinkingMode)) {
            Map<String, Object> thinkingParam = new HashMap<>();
            thinkingParam.put("type", "thinking-on".equals(thinkingMode) ? "enabled" : "disabled");
            extendedKwargs.put("thinking", thinkingParam);
        } else if ("qwen-on".equals(thinkingMode) || "qwen-off".equals(thinkingMode)) {
            extendedKwargs.put("enable_thinking", "qwen-on".equals(thinkingMode));
        }
        return extendedKwargs;
    }

    /**
     * Sends a hard-to-refuse probe to verify the client forwards tools to the LLM.
     *
     * <p>The probe constructs a minimalist tool ({@value #PROBE_TOOL_NAME}) and a
     * system/user prompt pair that leaves the LLM little room to avoid calling it.
     * If the response has null or empty tool_calls, the client is bypassing tools.
     *
     * @param request resolved model call parameters for the probe
     * @throws ToolCallingBypassException when the client fails the probe
     * @throws Exception                  propagated from the underlying client
     */
    private void doProbe(ProbeRequest request) throws Exception {
        // Build a minimalist probe tool with a forced-call description.
        ToolInfo probeTool = ToolInfo.builder().type("function").name(PROBE_TOOL_NAME)
                .description("Probe tool for tool-calling capability verification. "
                        + "Call this tool with reason='probe' immediately.")
                .parameters(Map.of("type", "object", "properties",
                        Map.of("reason", Map.of("type", "string", "description", "Reason for probe")), "required",
                        List.of("reason")))
                .build();

        // Build probe messages that leave the LLM no room to refuse calling the tool.
        List<BaseMessage> probeMessages = List.of(
                new SystemMessage("You are a function-calling assistant. "
                        + "You MUST call the provided function with reason='probe'."),
                new UserMessage("Call " + PROBE_TOOL_NAME + " with reason='probe' " + "and return its output."));

        // Probe goes through super.invoke() which delegates to this.client.invoke().
        AssistantMessage probeResponse = super.invoke(probeMessages, List.of(probeTool), request.temperature,
                request.maxTokens, request.model, request.n, request.stop, request.parser, request.topP,
                request.kwargs);

        // Null response is an unambiguous failure.
        if (probeResponse == null) {
            throw new ToolCallingBypassException(
                    "Probe returned null response — cannot verify tool-calling capability.");
        }

        // Check for tool_calls in the probe response.
        List<ToolCall> calls = probeResponse.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            throw new ToolCallingBypassException(
                    "Tool-calling bypass detected: the underlying BaseModelClient discarded "
                            + "the tools parameter. Response has no tool_calls despite a forced probe. "
                            + "Verify that the BaseModelClient implementation ("
                            + getModelClientConfig().getClientProvider() + ") forwards tools to the LLM API.");
        }
    }

    private static final class ProbeRequest {
        private Float temperature;
        private Float maxTokens;
        private String model;
        private Integer n;
        private String stop;
        private BaseOutputParser parser;
        private Float topP;
        private Map<String, Object> kwargs;
    }
}
