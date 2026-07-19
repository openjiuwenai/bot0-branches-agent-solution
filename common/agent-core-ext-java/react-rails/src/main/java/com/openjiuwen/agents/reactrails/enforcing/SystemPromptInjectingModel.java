/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Injection-capable EnforcingModel — extends {@link ToolCallingEnforcingModel} with
 * optional system-prompt augmentation and a model-owned phase override channel.
 *
 * <p>Injection modes (set via {@link #setInjectionMode}):
 * <ul>
 *   <li><b>NONE</b> (default) — behaves exactly as {@link ToolCallingEnforcingModel}.</li>
 *   <li><b>SYSTEM_PROMPT_APPEND</b> — appends {@code systemPromptSuffix} to the
 *       {@link SystemMessage} on every {@code invoke()}.</li>
 *   <li><b>USER_MESSAGE_INJECT</b> — prepends a {@link UserMessage} carrying the
 *       current phase override text (set via {@link #setPhaseOverride}),
 *       read by {@link #consumePhaseOverride()} on each call.</li>
 *   <li><b>PLAN_MODE</b> — replaces the first {@link SystemMessage} with a divergent
 *       exploration framing (PLAN phase). Also consumes phaseOverride if set.</li>
 *   <li><b>BUILD_MODE</b> — replaces the first {@link SystemMessage} with a convergent
 *       execution framing (BUILD phase). Also consumes phaseOverride if set.</li>
 * </ul>
 *
 * <p>The model-owned phase-override channel allows a {@link com.openjiuwen.core.singleagent.rail.AgentRail}
 * (e.g. {@link com.openjiuwen.agents.reactrails.verification.StagnationDetectionRail})
 * to communicate phase transitions to the model layer without jar modification:
 * <pre>{@code
 *   // In afterModelCall:
 *   model.injectionState().setPhaseOverride("BREAK_LOOP: your output is repetitive");
 *   // Next invoke() will inject the brake prompt as a UserMessage
 * }</pre>
 *
 * <p>State is owned by each model instance and isolated per invocation thread. Prompt-aware
 * rails must receive the target model's {@link #injectionState()} explicitly.
 *
 * @since 2026-07
 */
public class SystemPromptInjectingModel extends ToolCallingEnforcingModel {
    /**
     * Default injection mode.
     */
    public static final InjectionMode DEFAULT_MODE = InjectionMode.NONE;

    private static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * The "先扩后收" (widen then converge) first-principles strategy prompt
     * injected once into the SystemMessage on the first real {@link #invoke}
     * when {@link InjectionMode#FIRST_PRINCIPLES} is active.
     */
    private static final String FIRST_PRINCIPLES_PROMPT = "【第一性原理】您在解决问题时应遵循「先扩后收」的认知策略：" + LINE_SEPARATOR
            + "1. 【先扩】首先广泛探索可能的解决方案空间，从多个角度审视问题，提出发散性的思路和方法" + LINE_SEPARATOR + "2. 【后收】然后系统性地评估和对比各方案，收敛到最佳执行路径，深入实施"
            + LINE_SEPARATOR + LINE_SEPARATOR + "请在整个推理过程中有意识地应用这一策略，主动标记当前位置（扩/收阶段），确保不急于收敛也不过度发散。";

    /**
     * PLAN phase system prompt (divergent/exploratory framing).
     */
    private static final String PLAN_SYSTEM_PROMPT = "You are in the DIVERGENT EXPLORATION phase. "
            + "Analyze the problem from at least 3 different angles. "
            + "Each angle should have concrete reasoning, evidence, and " + "data — not just bullet titles. "
            + "Use available tools to gather real information. "
            + "Do NOT converge on a single answer yet — explore first.";

    /**
     * BUILD phase system prompt (convergent/execution framing).
     */
    private static final String BUILD_SYSTEM_PROMPT = "You are in the CONVERGENT EXECUTION phase. "
            + "Focus on producing a single complete answer that meets " + "all success criteria. "
            + "Incorporate insights from your earlier exploration. "
            + "Ensure every criterion is explicitly addressed. "
            + "If your current path cannot cover all criteria, call " + ReplanTool.TOOL_NAME + ".";

    private final AtomicBoolean firstPrinciplesDone = new AtomicBoolean(false);
    private final PromptInjectionState injectionState = new PromptInjectionState();

    private String systemPromptSuffix = "";

    /**
     * Constructs an injecting model. Delegates to {@link ToolCallingEnforcingModel}
     * which creates the real {@code BaseModelClient} via {@link Model#Model}.
     *
     * @param config        client-level configuration
     * @param requestConfig request-level configuration
     */
    public SystemPromptInjectingModel(ModelClientConfig config, ModelRequestConfig requestConfig) {
        super(config, requestConfig);
    }

    // ---- Model-owned channel ----

    /**
     * Returns the state channel to share with prompt-aware rails for this model.
     *
     * @return model-owned prompt injection state
     */
    public PromptInjectionState injectionState() {
        return injectionState;
    }

    /**
     * Set the phase override for the next invoke.
     *
     * @param text override text to inject on the next invocation
     */
    public void setPhaseOverride(String text) {
        injectionState.setPhaseOverride(text);
    }

    /**
     * Read and clear the phase override.
     *
     * @return previous phase override, or null when none is set
     */
    public String consumePhaseOverride() {
        return injectionState.consumePhaseOverride();
    }

    /**
     * Peek at the current phase override without consuming.
     *
     * @return current phase override, or null when none is set
     */
    public String peekPhaseOverride() {
        return injectionState.peekPhaseOverride();
    }

    /**
     * Set the injection mode for this model on the current invocation thread.
     *
     * @param m model default and current-thread injection mode
     */
    public void setInjectionMode(InjectionMode m) {
        injectionState.setConfiguredMode(m);
    }

    /**
     * Get current injection mode.
     *
     * @return current injection mode
     */
    public InjectionMode getInjectionMode() {
        return injectionState.getMode();
    }

    /**
     * Reset to defaults (NONE, no override).
     */
    public void resetToDefaults() {
        injectionState.reset();
    }

    // ---- Instance configuration ----

    /**
     * Set the system prompt suffix to append in SYSTEM_PROMPT_APPEND mode.
     * Text is appended to the first {@link SystemMessage} in the message list.
     *
     * @param suffix prompt suffix to append
     */
    public void setSystemPromptSuffix(String suffix) {
        this.systemPromptSuffix = suffix != null ? suffix : "";
    }

    // ---- Invoke override ----

    @Override
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
            Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs)
            throws Exception {
        Object effectiveMessages = prepareMessages(messages);
        return super.invoke(effectiveMessages, tools, temperature, topP, model, maxTokens, stop, outputParser, timeout,
                kwargs);
    }

    @Override
    public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
            String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
            Map<String, Object> kwargs) throws Exception {
        Object effectiveMessages = prepareMessages(messages);
        return super.stream(effectiveMessages, tools, temperature, topP, model, maxTokens, stop, outputParser, timeout,
                kwargs);
    }

    private Object prepareMessages(Object messages) {
        InjectionMode currentMode = injectionState.getMode();
        Object effectiveMessages = replaceSystemPrompt(messages, currentMode);
        effectiveMessages = injectPhaseOverride(effectiveMessages, currentMode);
        effectiveMessages = appendSystemPrompt(effectiveMessages, currentMode);
        return injectFirstPrinciplesIfNeeded(effectiveMessages, currentMode);
    }

    private static Object replaceSystemPrompt(Object messages, InjectionMode mode) {
        if (!isPlanOrBuild(mode) || !(messages instanceof List)) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        String replacementPrompt = mode == InjectionMode.PLAN_MODE ? PLAN_SYSTEM_PROMPT : BUILD_SYSTEM_PROMPT;
        for (int i = 0; i < msgList.size(); i++) {
            if (msgList.get(i) instanceof SystemMessage) {
                msgList.set(i, new SystemMessage(replacementPrompt));
                break;
            }
        }
        return msgList;
    }

    private Object injectPhaseOverride(Object messages, InjectionMode mode) {
        if (!(mode == InjectionMode.USER_MESSAGE_INJECT || isPlanOrBuild(mode)) || !(messages instanceof List)) {
            return messages;
        }
        String override = consumePhaseOverride();
        if (override == null || override.isEmpty()) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        msgList.add(new UserMessage("[系统提示] " + override));
        return msgList;
    }

    private Object appendSystemPrompt(Object messages, InjectionMode mode) {
        if (mode != InjectionMode.SYSTEM_PROMPT_APPEND || systemPromptSuffix.isEmpty() || !(messages instanceof List)) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        if (!msgList.isEmpty() && msgList.get(0) instanceof SystemMessage sysMsg) {
            String augmented = sysMsg.getContentAsString() + LINE_SEPARATOR + LINE_SEPARATOR + systemPromptSuffix;
            msgList.set(0, new SystemMessage(augmented));
        }
        return msgList;
    }

    private Object injectFirstPrinciplesIfNeeded(Object messages, InjectionMode mode) {
        if (mode != InjectionMode.FIRST_PRINCIPLES || !firstPrinciplesDone.compareAndSet(false, true)) {
            return messages;
        }
        return messages instanceof List ? injectFirstPrinciples(messages) : messages;
    }

    private static boolean isPlanOrBuild(InjectionMode mode) {
        return mode == InjectionMode.PLAN_MODE || mode == InjectionMode.BUILD_MODE;
    }

    // ==================================================================
    // First-principles prompt injection
    // ==================================================================

    /**
     * Injects the "先扩后收" first-principles prompt into the messages list
     * by appending to the existing SystemMessage, or inserting a new one
     * at position 0 if none exists.
     *
     * <p>Creates a new ArrayList copy — the original list from ReActAgent
     * ({@link com.openjiuwen.core.singleagent.rail.ModelCallInputs#getMessages})
     * is not modified in-place.
     *
     * @param messages the original messages object (must be {@code List<BaseMessage>})
     * @return a new or modified list with the first-principles prompt injected
     */
    private static Object injectFirstPrinciples(Object messages) {
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        boolean hasInjected = false;
        for (int i = 0; i < msgList.size(); i++) {
            if (!hasInjected && msgList.get(i) instanceof SystemMessage sys) {
                String original = sys.getContentAsString();
                String enhanced = (original != null ? original + LINE_SEPARATOR + LINE_SEPARATOR : "")
                        + FIRST_PRINCIPLES_PROMPT;
                msgList.set(i, new SystemMessage(enhanced));
                hasInjected = true;
            }
        }
        if (!hasInjected) {
            // No existing SystemMessage — insert at the beginning
            msgList.add(0, new SystemMessage(FIRST_PRINCIPLES_PROMPT));
        }
        return msgList;
    }

    /**
     * Injection modes.
     */
    public enum InjectionMode {
        /**
         * No injection — pass-through, identical to ToolCallingEnforcingModel.
         */
        NONE,
        /**
         * Append text to the existing SystemMessage.
         */
        SYSTEM_PROMPT_APPEND,
        /**
         * Inject a UserMessage with phase-override text before the call.
         */
        USER_MESSAGE_INJECT,
        /**
         * One-shot injection of the "先扩后收" (widen then converge)
         * first-principles strategy prompt into the SystemMessage on the
         * first real {@code invoke()}.
         */
        FIRST_PRINCIPLES,
        /**
         * Replace SystemMessage with divergent/exploratory framing (PLAN phase).
         */
        PLAN_MODE,
        /**
         * Replace SystemMessage with convergent/execution framing (BUILD phase).
         */
        BUILD_MODE
    }
}
