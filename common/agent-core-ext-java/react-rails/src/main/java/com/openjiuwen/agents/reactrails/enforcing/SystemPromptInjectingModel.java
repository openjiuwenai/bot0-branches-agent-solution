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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    // ---- System-prompt templates (issue-#15/#16 Phase2b-C1: externalized to classpath resources) ----
    // The 3 phase prompts live as UTF-8 text resources under prompts/ so they can be edited without
    // recompilation and overridden per-instance via setPlanSystemPrompt / setBuildSystemPrompt /
    // setFirstPrinciplesPrompt (config-consumer-reachability 双向, 铁律⑰). The BUILD prompt references
    // the replan tool via a ${replan_tool} placeholder substituted at load time with ReplanTool.TOOL_NAME,
    // preserving the issue-#16 single-source invariant — the prompt never holds a literal tool name; the
    // registry constant remains the only truth.
    /** Classpath directory holding the externalized system-prompt templates. */
    private static final String PROMPT_RESOURCE_DIR = "com/openjiuwen/agents/reactrails/enforcing/prompts/";

    /** Resource name for the PLAN (divergent exploration) system prompt. */
    private static final String PLAN_PROMPT_RESOURCE = "plan-system-prompt.txt";

    /** Resource name for the BUILD (convergent execution) system prompt. */
    private static final String BUILD_PROMPT_RESOURCE = "build-system-prompt.txt";

    /** Resource name for the one-shot first-principles strategy prompt. */
    private static final String FIRST_PRINCIPLES_PROMPT_RESOURCE = "first-principles-prompt.txt";

    /** Placeholder substituted with {@link ReplanTool#TOOL_NAME} at load time (single-source, issue #16). */
    private static final String REPLAN_TOOL_PLACEHOLDER = "${replan_tool}";

    private final AtomicBoolean firstPrinciplesDone = new AtomicBoolean(false);
    private final PromptInjectionState injectionState = new PromptInjectionState();

    /** Loaded PLAN prompt (classpath); replaced by {@link #planSystemPromptOverride} when set. */
    private final String planSystemPrompt = loadPrompt(PLAN_PROMPT_RESOURCE);

    /** Loaded BUILD prompt (classpath, {@code ${replan_tool}} substituted); replaced when set. */
    private final String buildSystemPrompt = substituteReplanTool(loadPrompt(BUILD_PROMPT_RESOURCE));

    /** Loaded first-principles prompt (classpath); replaced when set. */
    private final String firstPrinciplesPrompt = loadPrompt(FIRST_PRINCIPLES_PROMPT_RESOURCE);

    private volatile String planSystemPromptOverride;
    private volatile String buildSystemPromptOverride;
    private volatile String firstPrinciplesPromptOverride;

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

    // ---- Phase-prompt configuration (issue-#15/#16 Phase2b-C1) ----

    /**
     * Override the PLAN-phase system prompt (replaces the classpath default loaded from
     * {@code prompts/plan-system-prompt.txt}). Pass {@code null} to clear the override and
     * fall back to the classpath default.
     *
     * @param prompt custom PLAN prompt, or null to use the classpath default
     */
    public void setPlanSystemPrompt(String prompt) {
        this.planSystemPromptOverride = prompt;
    }

    /**
     * Override the BUILD-phase system prompt (replaces the classpath default loaded from
     * {@code prompts/build-system-prompt.txt}, which already has {@code ${replan_tool}}
     * substituted). The same {@code ${replan_tool}} → {@link ReplanTool#TOOL_NAME} substitution
     * is applied to the override so custom BUILD prompts keep the issue-#16 single-source
     * invariant (no literal tool name embedded in prompt text). Pass {@code null} to clear.
     *
     * @param prompt custom BUILD prompt, or null to use the classpath default
     */
    public void setBuildSystemPrompt(String prompt) {
        this.buildSystemPromptOverride = prompt == null ? null : substituteReplanTool(prompt);
    }

    /**
     * Override the one-shot first-principles strategy prompt (replaces the classpath default
     * loaded from {@code prompts/first-principles-prompt.txt}). Pass {@code null} to clear.
     *
     * @param prompt custom first-principles prompt, or null to use the classpath default
     */
    public void setFirstPrinciplesPrompt(String prompt) {
        this.firstPrinciplesPromptOverride = prompt;
    }

    /**
     * Effective PLAN prompt: per-instance override if set, else the classpath default.
     *
     * @return PLAN-phase system prompt actually used on the next PLAN_MODE invoke
     */
    private String effectivePlanPrompt() {
        return planSystemPromptOverride != null ? planSystemPromptOverride : planSystemPrompt;
    }

    /**
     * Effective BUILD prompt: per-instance override if set, else the classpath default.
     *
     * @return BUILD-phase system prompt actually used on the next BUILD_MODE invoke
     */
    private String effectiveBuildPrompt() {
        return buildSystemPromptOverride != null ? buildSystemPromptOverride : buildSystemPrompt;
    }

    /**
     * Effective first-principles prompt: per-instance override if set, else the classpath default.
     *
     * @return first-principles prompt actually used on the next FIRST_PRINCIPLES invoke
     */
    private String effectiveFirstPrinciplesPrompt() {
        return firstPrinciplesPromptOverride != null ? firstPrinciplesPromptOverride : firstPrinciplesPrompt;
    }

    /**
     * Load a prompt template from the classpath (UTF-8). Fail-loud with a fix hint if the
     * resource is missing — never silently fall back to an empty prompt.
     *
     * @param resourceName file name under {@link #PROMPT_RESOURCE_DIR}
     * @return prompt template content
     */
    private static String loadPrompt(String resourceName) {
        String path = PROMPT_RESOURCE_DIR + resourceName;
        try (InputStream in = SystemPromptInjectingModel.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("react-rails: system-prompt resource not found on classpath: "
                        + path + " (expected at src/main/resources/" + path + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("react-rails: failed to load system-prompt resource: " + path, e);
        }
    }

    /**
     * Substitute the {@code ${replan_tool}} placeholder with {@link ReplanTool#TOOL_NAME} —
     * keeps the BUILD prompt single-source with the tool registry (issue #16).
     *
     * @param prompt raw prompt possibly containing {@link #REPLAN_TOOL_PLACEHOLDER}
     * @return prompt with the placeholder resolved
     */
    private static String substituteReplanTool(String prompt) {
        return prompt.replace(REPLAN_TOOL_PLACEHOLDER, ReplanTool.TOOL_NAME);
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

    private Object replaceSystemPrompt(Object messages, InjectionMode mode) {
        if (!isPlanOrBuild(mode) || !(messages instanceof List)) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        String replacementPrompt = mode == InjectionMode.PLAN_MODE ? effectivePlanPrompt() : effectiveBuildPrompt();
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
    private Object injectFirstPrinciples(Object messages) {
        @SuppressWarnings("unchecked")
        List<BaseMessage> msgList = new ArrayList<>((List<BaseMessage>) messages);
        String firstPrinciples = effectiveFirstPrinciplesPrompt();
        boolean hasInjected = false;
        for (int i = 0; i < msgList.size(); i++) {
            if (!hasInjected && msgList.get(i) instanceof SystemMessage sys) {
                String original = sys.getContentAsString();
                String enhanced = (original != null ? original + LINE_SEPARATOR + LINE_SEPARATOR : "")
                        + firstPrinciples;
                msgList.set(i, new SystemMessage(enhanced));
                hasInjected = true;
            }
        }
        if (!hasInjected) {
            // No existing SystemMessage — insert at the beginning
            msgList.add(0, new SystemMessage(firstPrinciples));
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
