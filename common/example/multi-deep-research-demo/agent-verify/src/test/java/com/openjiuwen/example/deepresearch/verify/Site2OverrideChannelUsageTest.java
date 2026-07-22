/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Site 2 override-channel usage sample for toolless verify-agent-style consumers.
 *
 * <p><b>Context</b> — issue #16 Site 2 is a hardcoded {@code __replan__} reference inside the
 * BUILD-phase system prompt loaded by {@link SystemPromptInjectingModel}. The default classpath
 * resource {@code prompts/build-system-prompt.txt} carries a {@code ${replan_tool}} placeholder
 * which the model substitutes with {@link ReplanTool#TOOL_NAME} at load time — so a consumer
 * that wraps a plain {@code ChatModel} with {@code SystemPromptInjectingModel} and hits
 * BUILD_MODE without also registering {@code ReplanTool} sees a system prompt telling the LLM
 * to "call __replan__" against an agent that cannot dispatch it.
 *
 * <p>PR #66 (commit 5c036e4, Phase2b-C1) does not remove that default — the default is right for
 * mainstream ReAct consumers who register {@link ReplanTool}. Instead the fix externalizes the
 * three phase prompts and adds three setters ({@link SystemPromptInjectingModel#setBuildSystemPrompt},
 * {@code setPlanSystemPrompt}, {@code setFirstPrinciplesPrompt}) so <em>toolless</em> consumers can
 * substitute a prompt that does not reference the missing tool.
 *
 * <p>Verify-agent is the archetypal toolless consumer (see {@link VerifyAgentFactory} — attaches
 * {@code CriteriaReplanBridgeRail} only, never calls {@code ReplanTool.registerOnto}). Today
 * verify-agent does not compose {@link SystemPromptInjectingModel} at all, so Site 2 is
 * <em>not exercised</em>. If a future revision does compose it (to gain PLAN/BUILD phase
 * framing), the canonical toolless configuration is the one demonstrated here.
 *
 * <p>This test documents that canonical usage in code:
 * <ol>
 *   <li>{@link #withoutOverride_defaultBuildPromptStillReferencesReplanTool()} — evidence that the
 *       shipped default default {@code build-system-prompt.txt} contains {@code __replan__} after
 *       {@code ${replan_tool}} substitution. Toolless consumers see this by default. Fires on the
 *       exact channel PR #66 leaves in place — deliberate, not a bug.</li>
 *   <li>{@link #withOverride_setBuildSystemPromptRemovesDanglingReference()} — evidence that
 *       {@link SystemPromptInjectingModel#setBuildSystemPrompt(String)} defuses the dangling
 *       reference for toolless consumers. PR #66's override channel is the intended fix path.</li>
 * </ol>
 *
 * <p>Assertions target the {@link SystemMessage} content actually delivered to the underlying
 * {@code BaseModelClient} (captured via a stub factory), mirroring the content-IFF assertion
 * pattern PR #66 introduced in {@code buildModeReplacesSystemMessageWithConvergentExecution}.
 */
class Site2OverrideChannelUsageTest {
    @Test
    void withoutOverride_defaultBuildPromptStillReferencesReplanTool() throws Exception {
        // Given: toolless-style consumer wraps ChatModel with SystemPromptInjectingModel,
        // does NOT call setBuildSystemPrompt. Simulates verify-agent + BUILD phase.
        String provider = "site2-default-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerCapturingProvider(provider);
        SystemPromptInjectingModel model = buildModel(provider);
        model.setInjectionMode(InjectionMode.BUILD_MODE);

        // When: BUILD_MODE invoke — model replaces the first SystemMessage with effectiveBuildPrompt()
        List<BaseMessage> messages = List.of(new SystemMessage("verify-agent original system prompt"),
                new UserMessage("produce the final judgement"));
        model.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        // Then: injected SystemMessage carries the shipped default — contains __replan__ literally,
        // because build-system-prompt.txt has "${replan_tool}" replaced with ReplanTool.TOOL_NAME
        // ("__replan__") at load time. This is the dangling reference toolless consumers inherit.
        String systemContent = extractSystemContent(captured.get());
        assertThat(systemContent).as("BUILD_MODE must replace (not append to) the caller's system prompt")
                .doesNotContain("verify-agent original system prompt");
        assertThat(systemContent).as("default BUILD prompt hardcodes __replan__ (PR #66 preserves this "
                + "default for mainstream ReAct consumers; toolless consumers must override)")
                .contains(ReplanTool.TOOL_NAME)
                .contains("__replan__");
    }

    @Test
    void withOverride_setBuildSystemPromptRemovesDanglingReference() throws Exception {
        // Given: same toolless-style wrap, this time WITH setBuildSystemPrompt override — the
        // canonical usage pattern for toolless consumers per PR #66 (commit 5c036e4).
        String provider = "site2-overridden-" + System.nanoTime();
        AtomicReference<List<?>> captured = registerCapturingProvider(provider);
        SystemPromptInjectingModel model = buildModel(provider);
        model.setInjectionMode(InjectionMode.BUILD_MODE);
        model.setBuildSystemPrompt("You are in the CONVERGENT EXECUTION phase of a judgement task. "
                + "Focus on producing a single complete verdict that meets all success criteria. "
                + "This agent has no tools — decide from the input alone; do NOT reference any tool calls.");

        // When
        List<BaseMessage> messages = List.of(new SystemMessage("verify-agent original system prompt"),
                new UserMessage("produce the final judgement"));
        model.invoke(messages, List.of(), 0.3f, null, "test-model", null, null, null, null, null);

        // Then: the override took over — dangling __replan__ reference is gone from the system prompt.
        // The CONVERGENT EXECUTION framing (BUILD_MODE identity) is preserved by the override text
        // so we still get the phase semantics, just without the missing-tool suggestion.
        String systemContent = extractSystemContent(captured.get());
        assertThat(systemContent).as("BUILD_MODE with override must still REPLACE the caller's system prompt")
                .doesNotContain("verify-agent original system prompt");
        assertThat(systemContent).as("setBuildSystemPrompt override must defuse the __replan__ dangling reference")
                .doesNotContain("__replan__");
        assertThat(systemContent).as(
                "override should preserve BUILD phase framing so the phase-machine still makes semantic sense")
                .contains("CONVERGENT EXECUTION");
    }

    // ---------- helpers (mirror SystemPromptInjectingModelTest patterns) ----------

    private static SystemPromptInjectingModel buildModel(String provider) {
        ModelClientConfig cliCfg = ModelClientConfig.builder().clientId(provider + "-" + System.nanoTime())
                .clientProvider(provider).apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        ModelRequestConfig reqCfg = ModelRequestConfig.builder().modelName("test-model").temperature(0.3)
                .maxTokens(200).build();
        return new SystemPromptInjectingModel(cliCfg, reqCfg);
    }

    private static AtomicReference<List<?>> registerCapturingProvider(String provider) {
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
                        return new AssistantMessage("Final judgement");
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

    private static String extractSystemContent(List<?> realMessages) {
        assertThat(realMessages).as("BUILD_MODE real invoke must reach the client").isNotNull();
        return realMessages.stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast).findFirst()
                .map(SystemMessage::getContentAsString).orElse("<no SystemMessage>");
    }

    /** Minimal BaseModelClient stub — only invoke is exercised; everything else throws. */
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
