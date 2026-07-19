/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SystemPromptInjectingModel 单元测试 — 验证注入模式 + 模型实例状态通道。
 *
 * <p>注意: 完整功能需要真实 ModelClient（LLM 调用），此测试只验证消息改造逻辑。
 * {@code invoke()} 的真实调用需要 e2e 测试。
 *
 * <p>四出口验证：
 * <ol>
 *   <li>NONE 模式 → 消息不变</li>
 *   <li>USER_MESSAGE_INJECT → phaseOverride 转换为 UserMessage 追加</li>
 *   <li>SYSTEM_PROMPT_APPEND → systemPromptSuffix 追加到 SystemMessage</li>
 *   <li>无 phaseOverride → USER_MESSAGE_INJECT 不注入额外消息</li>
 * </ol>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>剥 msgList.add(new UserMessage(...)) → 无额外 user message → RED</li>
 *   <li>剥 msgList.set(0, new SystemMessage(augmented)) → system 内容不变 → RED</li>
 * </ul>
 */
class SystemPromptInjectingModelTest {
    @Test
    void noneModeInjectionModeIsNone() {
        PromptInjectionState state = new PromptInjectionState();
        state.setMode(SystemPromptInjectingModel.InjectionMode.NONE);

        assertThat(state.getMode()).isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
    }

    @Test
    void resetToDefaultsClearsState() {
        PromptInjectionState state = new PromptInjectionState();
        state.setPhaseOverride("override");
        state.setMode(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);

        state.reset();

        assertThat(state.getMode()).isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
        assertThat(state.peekPhaseOverride()).isNull();
    }

    @Test
    void consumePhaseOverrideReadsAndClears() {
        PromptInjectionState state = new PromptInjectionState();
        state.setPhaseOverride("test-value");

        String read = state.consumePhaseOverride();
        assertThat(read).isEqualTo("test-value");

        // Second read should be null (consumed)
        assertThat(state.consumePhaseOverride()).isNull();
    }

    @Test
    void setInjectionModeAndGetRoundtrip() {
        PromptInjectionState state = new PromptInjectionState();
        state.setMode(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);
        assertThat(state.getMode()).isEqualTo(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);
    }

    @Test
    void phaseOverrideSetPeekCycle() {
        PromptInjectionState state = new PromptInjectionState();
        state.setPhaseOverride("BREAK_LOOP: stop repeating");

        String peeked = state.peekPhaseOverride();
        assertThat(peeked).isEqualTo("BREAK_LOOP: stop repeating");

        // Peek doesn't consume
        assertThat(state.peekPhaseOverride()).isNotNull();
    }

    @Test
    void successivePhaseOverridesLastWins() {
        PromptInjectionState state = new PromptInjectionState();
        state.setPhaseOverride("first");
        state.setPhaseOverride("second");

        assertThat(state.consumePhaseOverride()).isEqualTo("second");
    }

    @Test
    void modelInstancesDoNotShareInjectionState() {
        String firstProvider = "test-isolation-first-" + System.nanoTime();
        String secondProvider = "test-isolation-second-" + System.nanoTime();
        registerNoSystemProvider(firstProvider);
        registerNoSystemProvider(secondProvider);
        SystemPromptInjectingModel first = model(firstProvider);
        SystemPromptInjectingModel second = model(secondProvider);

        first.setInjectionMode(SystemPromptInjectingModel.InjectionMode.PLAN_MODE);
        first.setPhaseOverride("first-model-only");

        assertThat(second.getInjectionMode()).isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
        assertThat(second.peekPhaseOverride()).isNull();
    }

    @Test
    void configuredModeAppliesOnInvocationThreads() throws Exception {
        String provider = "test-thread-config-" + System.nanoTime();
        registerNoSystemProvider(provider);
        SystemPromptInjectingModel model = model(provider);
        model.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);
        SystemPromptInjectingModel.InjectionMode observed = runOnWorker(model::getInjectionMode);

        assertThat(observed).as("model configuration must be visible to request threads")
                .isEqualTo(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);
    }

    @Test
    void runtimeOverridesAreThreadLocal() throws Exception {
        PromptInjectionState state = new PromptInjectionState();
        state.setMode(SystemPromptInjectingModel.InjectionMode.BUILD_MODE);
        state.setPhaseOverride("current-thread-only");
        WorkerObservation observed = runOnWorker(
                () -> new WorkerObservation(state.getMode(), state.peekPhaseOverride()));

        assertThat(observed.mode()).isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
        assertThat(observed.override()).isNull();
    }

    @Test
    void systemPromptAppendDoesNotMutateCallerMessages() throws Exception {
        String provider = "test-system-append-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<?>> capturedMessages = new AtomicReference<>();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }
            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new StubModelClient(r, c) {
                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP,
                            String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                            Map<String, Object> kwargs) {
                        if (calls.getAndIncrement() == 0) {
                            return probeMessage();
                        }
                        capturedMessages.set(messages instanceof List ? (List<?>) messages : List.of());
                        return new AssistantMessage("answer");
                    }
                };
            }
        });
        SystemPromptInjectingModel model = model(provider);
        model.setInjectionMode(SystemPromptInjectingModel.InjectionMode.SYSTEM_PROMPT_APPEND);
        model.setSystemPromptSuffix("extension suffix");
        List<BaseMessage> callerMessages = new ArrayList<>(List.of(new SystemMessage("base system")));

        model.invoke(callerMessages, List.of(), 0.3f, null, null, null, null, null, null, null);

        assertThat(callerMessages.get(0).getContentAsString()).isEqualTo("base system");
        assertThat(capturedMessages.get()).first().isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.getContentAsString()).contains("base system")
                        .contains("extension suffix"));
    }

    @Test
    void streamInjectsPhaseOverrideBeforeDelegating() throws Exception {
        String provider = "test-stream-injection-" + System.nanoTime();
        AtomicReference<List<?>> streamedMessages = new AtomicReference<>();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }
            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new StubModelClient(r, c) {
                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP,
                            String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                            Map<String, Object> kwargs) {
                        return probeMessage();
                    }
                    @Override
                    public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature,
                            Float topP, String model, Integer maxTokens, String stop, BaseOutputParser outputParser,
                            Float timeout, Map<String, Object> kwargs) {
                        streamedMessages.set(messages instanceof List ? (List<?>) messages : List.of());
                        return List.of(AssistantMessageChunk.builder().content("streamed").build()).iterator();
                    }
                };
            }
        });
        SystemPromptInjectingModel model = model(provider);
        model.setInjectionMode(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);
        model.setPhaseOverride("stream-only-override");

        Iterator<AssistantMessageChunk> chunks = model.stream(List.of(new SystemMessage("system")), List.of(), 0.3f,
                null, null, null, null, null, null, null);

        assertThat(chunks.next().getContentAsString()).isEqualTo("streamed");
        assertThat(streamedMessages.get())
                .anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(UserMessage.class,
                        user -> assertThat(user.getContentAsString()).contains("stream-only-override")));
    }
    // FIRST_PRINCIPLES mode — one-shot "先扩后收" injection
    /**
     * FIRST_PRINCIPLES mode injects the first-principles prompt on the
     * first real invoke (after the probe), and does NOT inject again on
     * subsequent invokes (one-shot via AtomicBoolean CAS).
     *
     * <p>Mutation-RED (IFF 范式):
     * <ul>
     *   <li>剥 {@link SystemPromptInjectingModel#injectFirstPrinciples} 调用
     *       (line 240) → 首次 invoke 不注入 → 断言 "先扩后收" 不在消息中 → RED</li>
     *   <li>剥 {@code firstPrinciplesDone.compareAndSet(false, true)} CAS 保护
     *       (line 238) → 每次 invoke 都注入 → 第二次 invoke 消息出现 "先扩后收" → RED</li>
     *   <li>剥 {@code injectFirstPrinciples} 中 {@code SystemMessage} 追加逻辑
     *       (line 269-274) → system message 不变 → RED</li>
     * </ul>
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void firstPrinciplesModeOneShotInjectionOnFirstInvoke() throws Exception {
        String provider = "test-fp-" + System.nanoTime();
        FirstPrinciplesCapture capture = registerFirstPrinciplesProvider(provider);
        var enforcingModel = model(provider);
        enforcingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);

        // Build messages with a SystemMessage to receive the injection
        List<BaseMessage> messages = List.of(new SystemMessage("You are a helpful assistant."),
                new UserMessage("Analyze the current economic situation."));

        // First invoke: probe + real(with injection)
        enforcingModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        // Second invoke: only real(no probe, no injection)
        enforcingModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        // --- Assertions ---

        // First real call (client call #1, after probe call #0)
        List<?> firstMessages = capture.firstMessages.get();
        assertThat(firstMessages).isNotNull();
        boolean hasFirstFp = firstMessages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).anyMatch(m -> m.getContentAsString().contains("先扩后收"));
        assertThat(hasFirstFp).as("FIRST_PRINCIPLES injection must appear on first real invoke").isTrue();

        // Second real call (client call #2, from the second model invoke)
        // One-shot: the injection must NOT fire again, so the original
        // SystemMessage (without "先扩后收") passes through unchanged.
        List<?> secondMessages = capture.secondMessages.get();
        assertThat(secondMessages).isNotNull();

        boolean hasSecondFp = secondMessages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).anyMatch(m -> m.getContentAsString().contains("先扩后收"));
        assertThat(hasSecondFp)
                .as("FIRST_PRINCIPLES prompt must NOT appear on second invoke" + " (one-shot via AtomicBoolean CAS)")
                .isFalse();
    }

    /**
     * FIRST_PRINCIPLES mode with NO existing SystemMessage:
     * injectFirstPrinciples creates a new SystemMessage at position 0.
     *
     * <p>Mutation-RED: 剥 {@code injectFirstPrinciples} 中
     * {@code msgList.add(0, new SystemMessage(...))} 末尾插入逻辑 (line 279)
     * → 无 SystemMessage 时不插入 → RED
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void firstPrinciplesModeCreatesSystemMessageWhenNoneExists() throws Exception {
        String provider = "test-fp-nosys-" + System.nanoTime();
        AtomicReference<List<?>> capturedMessages = registerNoSystemProvider(provider);
        var enforcingModel = model(provider);
        enforcingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);

        // Messages with NO SystemMessage — only UserMessage
        List<BaseMessage> messages = List.of(new UserMessage("Analyze the current economic situation."));

        enforcingModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        List<?> captured = capturedMessages.get();
        assertThat(captured).isNotNull();
        Object firstMessage = captured.get(0);
        assertThat(firstMessage).as("First message must be a SystemMessage when none existed")
                .isInstanceOf(SystemMessage.class);
        if (!(firstMessage instanceof SystemMessage systemMessage)) {
            throw new AssertionError("first message should be a SystemMessage");
        }
        String content = systemMessage.getContentAsString();
        assertThat(content).as("Created SystemMessage must contain the first-principles prompt").contains("先扩后收")
                .contains("第一性原理");
    }

    // ================================================================
    // PLAN_MODE / BUILD_MODE content-IFF 承重测试（issue-#15/#16 Phase2a，verify MAJOR）
    // ================================================================
    // 治 verify MAJOR 弱断言：之前所有 PLAN_MODE/BUILD_MODE 测试（modelInstancesDoNotShareInjectionState
    // 设 PLAN_MODE 只查状态隔离；runtimeOverridesAreThreadLocal 设 BUILD_MODE 只查线程局部性）只断
    // injectionState.getMode() 枚举，从不断言 replaceSystemPrompt 实际把哪个字符串塞进 SystemMessage
    // → PLAN_SYSTEM_PROMPT / BUILD_SYSTEM_PROMPT 内容互换测试仍绿（恒真）。下方两测试把 MODE 绑定到
    // SystemMessage 实际内容，消灭恒真。

    /**
     * PLAN_MODE must REPLACE the first SystemMessage with the divergent-exploration framing.
     *
     * <p>content-IFF：PLAN_MODE → 首个 SystemMessage contains "DIVERGENT EXPLORATION" +
     * doesNotContain "CONVERGENT EXECUTION" + doesNotContain 原始 "base system prompt"（证替换非追加）。
     *
     * <p>mutation-RED（IFF 范式，剥→RED 证非恒真）:
     * <ul>
     *   <li>剥 {@link SystemPromptInjectingModel} 的 {@code replaceSystemPrompt} 中
     *       {@code msgList.set(i, new SystemMessage(replacementPrompt))} → SystemMessage 保持
     *       "base system prompt" → contains("DIVERGENT EXPLORATION") 失败 → RED</li>
     *   <li>互换 PLAN_SYSTEM_PROMPT / BUILD_SYSTEM_PROMPT 常量内容 → PLAN_MODE 拿到 CONVERGENT →
     *       doesNotContain("CONVERGENT EXECUTION") 失败 → RED</li>
     *   <li>互换 ternary {@code mode == PLAN_MODE ? BUILD : PLAN} → PLAN_MODE 拿到 CONVERGENT → RED</li>
     * </ul>
     *
     * <p>注：registerNoSystemProvider 名字历史遗留（FIRST_PRINCIPLES 无 SystemMessage 场景），
     * 功能是捕获 real-call（probe 后那次）的 messages，与有无 SystemMessage 无关，复用避免重复。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void planModeReplacesSystemMessageWithDivergentExploration() throws Exception {
        String provider = "test-plan-content-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerNoSystemProvider(provider);
        SystemPromptInjectingModel planModel = model(provider);
        planModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.PLAN_MODE);

        List<BaseMessage> messages = List.of(new SystemMessage("base system prompt"),
                new UserMessage("analyze the problem from multiple angles"));
        planModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        List<?> realMessages = captured.get();
        assertThat(realMessages).as("PLAN_MODE real invoke must reach the client").isNotNull();
        assertThat(realMessages.get(0)).as("first message must remain a SystemMessage after PLAN replacement")
                .isInstanceOf(SystemMessage.class);
        String systemContent = realMessages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().map(SystemMessage::getContentAsString)
                .orElse("<no SystemMessage>");
        assertThat(systemContent).as("PLAN_MODE must inject the divergent-exploration framing")
                .contains("DIVERGENT EXPLORATION");
        assertThat(systemContent).as(
                "PLAN_MODE must NOT inject the convergent framing (content-IFF: catches swapped constants/ternary)")
                .doesNotContain("CONVERGENT EXECUTION");
        assertThat(systemContent).as("PLAN_MODE must replace (not append to) the original system prompt")
                .doesNotContain("base system prompt");
        // mutation-RED: strip msgList.set(i, new SystemMessage(replacementPrompt)) in replaceSystemPrompt
        // → systemContent stays "base system prompt" → first contains("DIVERGENT EXPLORATION") RED
    }

    /**
     * BUILD_MODE must REPLACE the first SystemMessage with the convergent-execution framing.
     * Symmetric content-IFF to {@link #planModeReplacesSystemMessageWithDivergentExploration}.
     *
     * <p>BUILD_MODE → 首个 SystemMessage contains "CONVERGENT EXECUTION" + doesNotContain
     * "DIVERGENT EXPLORATION" + doesNotContain 原始 "base system prompt"。
     *
     * <p>mutation-RED：剥 replaceSystemPrompt 的 msgList.set → RED；互换常量/ternary → RED。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void buildModeReplacesSystemMessageWithConvergentExecution() throws Exception {
        String provider = "test-build-content-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerNoSystemProvider(provider);
        SystemPromptInjectingModel buildModel = model(provider);
        buildModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.BUILD_MODE);

        List<BaseMessage> messages = List.of(new SystemMessage("base system prompt"),
                new UserMessage("produce the single best final answer"));
        buildModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        List<?> realMessages = captured.get();
        assertThat(realMessages).as("BUILD_MODE real invoke must reach the client").isNotNull();
        assertThat(realMessages.get(0)).as("first message must remain a SystemMessage after BUILD replacement")
                .isInstanceOf(SystemMessage.class);
        String systemContent = realMessages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().map(SystemMessage::getContentAsString)
                .orElse("<no SystemMessage>");
        assertThat(systemContent).as("BUILD_MODE must inject the convergent-execution framing")
                .contains("CONVERGENT EXECUTION");
        assertThat(systemContent).as(
                "BUILD_MODE must NOT inject the divergent framing (content-IFF: catches swapped constants/ternary)")
                .doesNotContain("DIVERGENT EXPLORATION");
        assertThat(systemContent).as("BUILD_MODE must replace (not append to) the original system prompt")
                .doesNotContain("base system prompt");
        // mutation-RED: strip msgList.set(i, new SystemMessage(replacementPrompt)) in replaceSystemPrompt
        // → systemContent stays "base system prompt" → first contains("CONVERGENT EXECUTION") RED
    }

    // ================================================================
    // Phase-prompt config-consumer-reachability 双向（铁律⑰，Phase2b-C1）
    // =================================================================
    // 三 setter（setPlanSystemPrompt / setBuildSystemPrompt / setFirstPrinciplesPrompt）双向 IFF：
    // true → 行为 X（override 内容到 SystemMessage）/ false → 无 X（classpath default，由上方 content-IFF
    // 两测试 + FIRST_PRINCIPLES 测试证）。消灭死配置（planGenerationEnabled 曾硬编码 false 的同类反模式）。

    /**
     * setPlanSystemPrompt override REACHES replaceSystemPrompt（config-consumer-reachability 铁律⑰）。
     *
     * <p>双向：set override + PLAN_MODE → SystemMessage contains override marker + doesNotContain
     * classpath default "DIVERGENT EXPLORATION"（开关 true → 行为 X）。不 set → default（开关 false）。
     *
     * <p>mutation-RED：剥 {@code effectivePlanPrompt} 的 override 分支（恒返 planSystemPrompt）→
     * override 无效 → contains(override marker) 失败 → RED。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void planSystemPromptOverrideReachesReplaceSystemPrompt() throws Exception {
        String provider = "test-plan-override-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerNoSystemProvider(provider);
        SystemPromptInjectingModel planModel = model(provider);
        planModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.PLAN_MODE);
        planModel.setPlanSystemPrompt("CUSTOM_PLAN_PROMPT_xyz_unique_marker");

        List<BaseMessage> messages = List.of(new SystemMessage("base system prompt"), new UserMessage("go"));
        planModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        String systemContent = captured.get().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().map(SystemMessage::getContentAsString)
                .orElse("<no SystemMessage>");
        assertThat(systemContent).as("setPlanSystemPrompt override must reach the replaced SystemMessage (true→X)")
                .contains("CUSTOM_PLAN_PROMPT_xyz_unique_marker");
        assertThat(systemContent).as("override must fully replace the classpath default (not merge)")
                .doesNotContain("DIVERGENT EXPLORATION");
        // mutation-RED: strip `planSystemPromptOverride != null ? planSystemPromptOverride :` in
        // effectivePlanPrompt → override ignored → contains("CUSTOM_PLAN_PROMPT_xyz_unique_marker") RED
    }

    /**
     * setBuildSystemPrompt override REACHES replaceSystemPrompt（铁律⑰，且证 override 也走
     * ${replan_tool} 替换保单源）。
     *
     * <p>双向：set override + BUILD_MODE → SystemMessage contains override marker。default 由
     * buildModeReplacesSystemMessageWithConvergentExecution 证。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void buildSystemPromptOverrideReachesReplaceSystemPrompt() throws Exception {
        String provider = "test-build-override-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerNoSystemProvider(provider);
        SystemPromptInjectingModel buildModel = model(provider);
        buildModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.BUILD_MODE);
        buildModel.setBuildSystemPrompt("CUSTOM_BUILD_PROMPT_xyz_unique_marker");

        List<BaseMessage> messages = List.of(new SystemMessage("base system prompt"), new UserMessage("go"));
        buildModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        String systemContent = captured.get().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().map(SystemMessage::getContentAsString)
                .orElse("<no SystemMessage>");
        assertThat(systemContent).as("setBuildSystemPrompt override must reach the replaced SystemMessage (true→X)")
                .contains("CUSTOM_BUILD_PROMPT_xyz_unique_marker");
        assertThat(systemContent).as("override must fully replace the classpath default (not merge)")
                .doesNotContain("CONVERGENT EXECUTION");
        // mutation-RED: strip the override branch in effectiveBuildPrompt → RED
    }

    /**
     * setBuildSystemPrompt override 走 ${replan_tool} 替换（issue#16 单源不变式对自定义 prompt 同样成立）。
     *
     * <p>override 含 {@code ${replan_tool}} 占位符 → SystemMessage contains ReplanTool.TOOL_NAME 字面
     * （替换发生），doesNotContain 占位符本身。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void buildSystemPromptOverrideAppliesReplanToolSubstitution() throws Exception {
        String provider = "test-build-replan-subst-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerNoSystemProvider(provider);
        SystemPromptInjectingModel buildModel = model(provider);
        buildModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.BUILD_MODE);
        buildModel.setBuildSystemPrompt("CUSTOM prompt calling ${replan_tool} now.");

        List<BaseMessage> messages = List.of(new SystemMessage("base"), new UserMessage("go"));
        buildModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        String systemContent = captured.get().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().map(SystemMessage::getContentAsString)
                .orElse("<no SystemMessage>");
        assertThat(systemContent).as("override ${replan_tool} must be substituted with the registry tool name")
                .contains(ReplanTool.TOOL_NAME)
                .doesNotContain("${replan_tool}");
        // mutation-RED: strip substituteReplanTool(prompt) in setBuildSystemPrompt → override keeps
        // literal ${replan_tool} → doesNotContain("${replan_tool}") fails → RED
        //
        // NOTE (4-lens verify, Lens 3 #2): this test stays GREEN under the *effectiveBuildPrompt
        // override-branch* mutation — the classpath default BUILD prompt already has ${replan_tool}
        // substituted at load time, so contains(__replan__).doesNotContain(${replan_tool}) holds even
        // with the default. THIS test's own load-bearing mutation is the substituteReplanTool call in
        // setBuildSystemPrompt above; buildSystemPromptOverrideReachesReplaceSystemPrompt is the test
        // that goes RED under the effectiveBuildPrompt mutation.
    }

    /**
     * setFirstPrinciplesPrompt override REACHES the one-shot first-principles injection（铁律⑰）。
     *
     * <p>双向：set override + FIRST_PRINCIPLES → 首次 real invoke 的 SystemMessage contains override marker。
     * default 由 firstPrinciplesModeOneShotInjectionOnFirstInvoke 证（contains "先扩后收"）。
     *
     * @throws Exception when model invocation fails unexpectedly
     */
    @Test
    void firstPrinciplesPromptOverrideReachesInjection() throws Exception {
        String provider = "test-fp-override-" + System.nanoTime();
        FirstPrinciplesCapture capture = registerFirstPrinciplesProvider(provider);
        SystemPromptInjectingModel fpModel = model(provider);
        fpModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);
        fpModel.setFirstPrinciplesPrompt("CUSTOM_FP_PROMPT_xyz_unique_marker");

        List<BaseMessage> messages = List.of(new SystemMessage("You are a helpful assistant."),
                new UserMessage("analyze"));
        fpModel.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        List<?> firstMessages = capture.firstMessages.get();
        assertThat(firstMessages).as("FIRST_PRINCIPLES override test must capture the first real invoke").isNotNull();
        boolean hasOverride = firstMessages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .anyMatch(m -> m.getContentAsString().contains("CUSTOM_FP_PROMPT_xyz_unique_marker"));
        assertThat(hasOverride).as("setFirstPrinciplesPrompt override must reach the first-real-invoke SystemMessage")
                .isTrue();
        // mutation-RED: strip the override branch in effectiveFirstPrinciplesPrompt → override ignored
        // → hasOverride false → RED
    }

    private static SystemPromptInjectingModel model(String provider) {
        var cliCfg = ModelClientConfig.builder().clientId(provider + "-" + System.nanoTime()).clientProvider(provider)
                .apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("test-model").temperature(0.3).maxTokens(200).build();
        return new SystemPromptInjectingModel(cliCfg, reqCfg);
    }

    private static FirstPrinciplesCapture registerFirstPrinciplesProvider(String provider) {
        DefaultModelClientFactories.ensureRegistered();
        FirstPrinciplesCapture capture = new FirstPrinciplesCapture();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new StubModelClient(r, c) {
                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens,
                            String model, Integer n, String stop, BaseOutputParser parser, Float topP,
                            Map<String, Object> kwargs) {
                        int count = capture.callCount.getAndIncrement();
                        if (count == 0) {
                            return probeMessage();
                        }
                        captureRealInvoke(messages, count, capture);
                        return new AssistantMessage("Final answer for call #" + count);
                    }
                };
            }
        });
        return capture;
    }

    private static AtomicReference<List<?>> registerNoSystemProvider(String provider) {
        DefaultModelClientFactories.ensureRegistered();
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<List<?>> capturedMessages = new AtomicReference<>();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new StubModelClient(r, c) {
                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens,
                            String model, Integer n, String stop, BaseOutputParser parser, Float topP,
                            Map<String, Object> kwargs) {
                        if (callCount.getAndIncrement() == 0) {
                            return probeMessage();
                        }
                        capturedMessages.set(messages instanceof List ? (List<?>) messages : List.of());
                        return new AssistantMessage("Final answer");
                    }
                };
            }
        });
        return capturedMessages;
    }

    private static AssistantMessage probeMessage() {
        AssistantMessage msg = new AssistantMessage("__probe__");
        msg.setToolCalls(List.of(new ToolCall("1", "function", "__probe_tool__", "{\"reason\":\"probe\"}", 0)));
        return msg;
    }

    private static void captureRealInvoke(Object messages, int count, FirstPrinciplesCapture capture) {
        if (count == 1) {
            capture.firstMessages.set(messages instanceof List ? (List<?>) messages : List.of());
        } else if (count == 2) {
            capture.secondMessages.set(messages instanceof List ? (List<?>) messages : List.of());
        } else {
            return;
        }
    }

    private static <T> T runOnWorker(Callable<T> task) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            return executor.submit(task).get();
        } finally {
            executor.shutdownNow();
        }
    }

    private record WorkerObservation(SystemPromptInjectingModel.InjectionMode mode, String override) {
    }

    private static final class FirstPrinciplesCapture {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicReference<List<?>> firstMessages = new AtomicReference<>();
        private final AtomicReference<List<?>> secondMessages = new AtomicReference<>();
    }

    private abstract static class StubModelClient extends BaseModelClient {
        StubModelClient(ModelRequestConfig requestConfig, ModelClientConfig clientConfig) {
            super(requestConfig, clientConfig);
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object a, Object b, Float cc, Float d, String e, Integer f,
                String g, BaseOutputParser h, Float i, Map<String, Object> j) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> a, String b, String c, String d, int e,
                boolean isF, boolean isG, int h, Map<String, Object> i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> a, String b, String c, String d,
                Map<String, Object> e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> a, String b, String c, String d, String e,
                String f, int g, boolean isH, boolean isI, String j, Integer k, Map<String, Object> l) {
            throw new UnsupportedOperationException();
        }
    }
}
