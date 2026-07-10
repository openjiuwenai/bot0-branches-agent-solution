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
import com.openjiuwen.core.foundation.llm.schema.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Injection-capable EnforcingModel — extends {@link ToolCallingEnforcingModel} with
 * optional system-prompt augmentation and a static phase override channel.
 *
 * <p>Injection modes (set via {@link #setInjectionMode}):
 * <ul>
 *   <li><b>NONE</b> (default) — behaves exactly as {@link ToolCallingEnforcingModel}.</li>
 *   <li><b>SYSTEM_PROMPT_APPEND</b> — appends {@code systemPromptSuffix} to the
 *       {@link SystemMessage} on every {@code invoke()}.</li>
 *   <li><b>USER_MESSAGE_INJECT</b> — prepends a {@link UserMessage} carrying the
 *       current phase override text (set via static {@link #setPhaseOverride}),
 *       read by {@link #consumePhaseOverride()} on each call.</li>
 *   <li><b>PLAN_MODE</b> — replaces the first {@link SystemMessage} with a divergent
 *       exploration framing (PLAN phase). Also consumes phaseOverride if set.</li>
 *   <li><b>BUILD_MODE</b> — replaces the first {@link SystemMessage} with a convergent
 *       execution framing (BUILD phase). Also consumes phaseOverride if set.</li>
 * </ul>
 *
 * <p>The static phase-override channel allows a {@link com.openjiuwen.core.singleagent.rail.AgentRail}
 * (e.g. {@link com.openjiuwen.agents.reactrails.verification.StagnationDetectionRail})
 * to communicate phase transitions to the model layer without jar modification:
 * <pre>{@code
 *   // In afterModelCall:
 *   SystemPromptInjectingModel.setPhaseOverride("BREAK_LOOP: your output is repetitive");
 *   // Next invoke() will inject the brake prompt as a UserMessage
 * }</pre>
 *
 * <p>Thread-safe via {@link AtomicReference} — the phase override is stored per-class,
 * so all agents sharing this class see the same override. In a multi-agent scenario
 * each agent should have its own model instance; the static channel is the
 * cross-hook communication primitive.
 *
 * @since 2026-07
 */
public class SystemPromptInjectingModel extends ToolCallingEnforcingModel {

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
        // PLAN mode: divergent/exploratory framing
        /**
         * Replace SystemMessage with divergent/exploratory framing (PLAN phase).
         */
        PLAN_MODE,
        /**
         * Replace SystemMessage with convergent/execution framing (BUILD phase).
         */
        BUILD_MODE
    }

    /**
     * Default injection mode.
     */
    public static final InjectionMode DEFAULT_MODE = InjectionMode.NONE;

    private static final AtomicReference<String> PHASE_OVERRIDE = new AtomicReference<>(null);
    private static final AtomicReference<InjectionMode> MODE = new AtomicReference<>(DEFAULT_MODE);
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
            + "If your current path cannot cover all criteria, call __replan__.";

    private final AtomicBoolean firstPrinciplesDone = new AtomicBoolean(false);

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

    // ---- Static channel (thread-safe) ----

    /**
     * Set the phase override for the next invoke.
     *
     * @param text override text to inject on the next invocation
     */
    public static void setPhaseOverride(String text) {
        PHASE_OVERRIDE.set(text);
    }

    /**
     * Read and clear the phase override.
     *
     * @return previous phase override, or null when none is set
     */
    public static String consumePhaseOverride() {
        return PHASE_OVERRIDE.getAndSet(null);
    }

    /**
     * Peek at the current phase override without consuming.
     *
     * @return current phase override, or null when none is set
     */
    public static String peekPhaseOverride() {
        return PHASE_OVERRIDE.get();
    }

    /**
     * Set global injection mode.
     *
     * @param m injection mode to apply globally
     */
    public static void setInjectionMode(InjectionMode m) {
        MODE.set(m);
    }

    /**
     * Get current injection mode.
     *
     * @return current injection mode
     */
    public static InjectionMode getInjectionMode() {
        return MODE.get();
    }

    /**
     * Reset to defaults (NONE, no override).
     */
    public static void resetToDefaults() {
        MODE.set(DEFAULT_MODE);
        PHASE_OVERRIDE.set(null);
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
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens, String model,
            Integer n, String stop, BaseOutputParser parser, Float topP, Map<String, Object> kwargs) throws Exception {

        Object effectiveMessages = messages;
        InjectionMode currentMode = MODE.get();

        // PLAN_MODE / BUILD_MODE: replace SystemMessage content entirely
        if ((currentMode == InjectionMode.PLAN_MODE || currentMode == InjectionMode.BUILD_MODE)
                && effectiveMessages instanceof List) {
            @SuppressWarnings("unchecked")
            List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) effectiveMessages);
            String replacementPrompt = (currentMode == InjectionMode.PLAN_MODE)
                    ? PLAN_SYSTEM_PROMPT
                    : BUILD_SYSTEM_PROMPT;
            for (int i = 0; i < msgList.size(); i++) {
                if (msgList.get(i) instanceof SystemMessage) {
                    msgList.set(i, new SystemMessage(replacementPrompt));
                    break;
                }
            }
            effectiveMessages = msgList;
        }

        // USER_MESSAGE_INJECT: inject phase-override text as UserMessage
        if (currentMode == InjectionMode.USER_MESSAGE_INJECT && effectiveMessages instanceof List) {
            String override = consumePhaseOverride();
            if (override != null && !override.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) effectiveMessages);
                msgList.add(new UserMessage("[系统提示] " + override));
                effectiveMessages = msgList;
            }
        }

        // PLAN_MODE / BUILD_MODE: also consume phaseOverride if set
        if ((currentMode == InjectionMode.PLAN_MODE || currentMode == InjectionMode.BUILD_MODE)
                && effectiveMessages instanceof List) {
            String override = consumePhaseOverride();
            if (override != null && !override.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) effectiveMessages);
                msgList.add(new UserMessage("[系统提示] " + override));
                effectiveMessages = msgList;
            }
        }

        // SYSTEM_PROMPT_APPEND: append to existing SystemMessage
        if (currentMode == InjectionMode.SYSTEM_PROMPT_APPEND && !systemPromptSuffix.isEmpty()
                && effectiveMessages instanceof List) {
            @SuppressWarnings("unchecked")
            List<BaseMessage> msgList = (List<BaseMessage>) effectiveMessages;
            if (!msgList.isEmpty() && msgList.get(0) instanceof SystemMessage sysMsg) {
                String augmented = sysMsg.getContentAsString() + LINE_SEPARATOR + LINE_SEPARATOR + systemPromptSuffix;
                msgList.set(0, new SystemMessage(augmented));
            }
        }

        // FIRST_PRINCIPLES: one-shot injection of "先扩后收" strategy prompt
        if (currentMode == InjectionMode.FIRST_PRINCIPLES && firstPrinciplesDone.compareAndSet(false, true)
                && effectiveMessages instanceof List) {
            effectiveMessages = injectFirstPrinciples(effectiveMessages);
        }

        // Delegate to super (ToolCallingEnforcingModel) which does probe + real invoke
        return super.invoke(effectiveMessages, tools, temperature, maxTokens, model, n, stop, parser, topP, kwargs);
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
        boolean injected = false;
        for (int i = 0; i < msgList.size(); i++) {
            if (!injected && msgList.get(i) instanceof SystemMessage sys) {
                String original = sys.getContentAsString();
                String enhanced = (original != null ? original + LINE_SEPARATOR + LINE_SEPARATOR : "")
                        + FIRST_PRINCIPLES_PROMPT;
                msgList.set(i, new SystemMessage(enhanced));
                injected = true;
            }
        }
        if (!injected) {
            // No existing SystemMessage — insert at the beginning
            msgList.add(0, new SystemMessage(FIRST_PRINCIPLES_PROMPT));
        }
        return msgList;
    }
}
