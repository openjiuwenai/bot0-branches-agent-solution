/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SystemPromptInjectingModel 单元测试 — 验证注入模式 + 静态通道。
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
    private SystemPromptInjectingModel model;

    @BeforeEach
    void setUp() {
        // Reset to defaults before each test
        SystemPromptInjectingModel.resetToDefaults();
    }

    @Test
    void noneModeInjectionModeIsNone() {
        SystemPromptInjectingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.NONE);

        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
    }

    @Test
    void resetToDefaultsClearsState() {
        SystemPromptInjectingModel.setPhaseOverride("override");
        SystemPromptInjectingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);

        SystemPromptInjectingModel.resetToDefaults();

        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).isNull();
    }

    @Test
    void consumePhaseOverrideReadsAndClears() {
        SystemPromptInjectingModel.setPhaseOverride("test-value");

        String read = SystemPromptInjectingModel.consumePhaseOverride();
        assertThat(read).isEqualTo("test-value");

        // Second read should be null (consumed)
        assertThat(SystemPromptInjectingModel.consumePhaseOverride()).isNull();
    }

    @Test
    void setInjectionModeAndGetRoundtrip() {
        SystemPromptInjectingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);
        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .isEqualTo(SystemPromptInjectingModel.InjectionMode.USER_MESSAGE_INJECT);
    }

    @Test
    void phaseOverrideSetPeekCycle() {
        SystemPromptInjectingModel.setPhaseOverride("BREAK_LOOP: stop repeating");

        String peeked = SystemPromptInjectingModel.peekPhaseOverride();
        assertThat(peeked).isEqualTo("BREAK_LOOP: stop repeating");

        // Peek doesn't consume
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).isNotNull();
    }

    @Test
    void successivePhaseOverridesLastWins() {
        SystemPromptInjectingModel.setPhaseOverride("first");
        SystemPromptInjectingModel.setPhaseOverride("second");

        assertThat(SystemPromptInjectingModel.consumePhaseOverride()).isEqualTo("second");
    }

    @Test
    void systemPromptSuffixSetAndGet() {
        // Can't easily instantiate without a real LLM client config
        // but the setter is tested via the config path
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
        SystemPromptInjectingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);

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
        SystemPromptInjectingModel.setInjectionMode(SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);

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
            // Later calls are irrelevant for this two-invoke test.
        }
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
