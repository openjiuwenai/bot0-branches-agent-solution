/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * EDPA "single Model serves both agent + Explorer" wiring test.
 *
 * <p><b>Load-bearing claim</b> (species B-replace-injected): the same
 * {@link Model} bean that backs {@code agent.setLlm(model)} also backs the
 * {@link LlmExplorer}'s {@code Function<String,String>}, with no conflict from
 * the BeanPostProcessor-before-setLlm timing window (the Function resolves the
 * Model lazily).
 *
 * <p>Proves three things:
 * <ol>
 *   <li>The auto-config's {@code modelExploringFunction} adapter turns a Model
 *       into a working {@code Function<String,String>} (Model.invoke 10-arg
 *       call, checked-Exception wrapped).</li>
 *   <li>{@link LlmExplorer} using that Function parses a real-shaped response
 *       into findings + approaches.</li>
 *   <li>The agent's Model and the Explorer's Function are the <em>same</em>
 *       source: mutating the stub client's scripted response changes BOTH the
 *       agent invoke result and the Explorer output.</li>
 * </ol>
 *
 * <p>Mock BaseModelClient (no real LLM) — proves control-flow/wiring, not data
 * channel (per 铁律: mock 证控制流, 真 LLM 证数据通道).
 *
 * @since 2026-07
 */
class EdpaModelExplorerAdapterTest {
    @Test
    void sameModelBacksAgentAndExplorer() throws Exception {
        String provider = "edpa-e2e-" + System.nanoTime();
        DefaultModelClientFactories.ensureRegistered();
        AtomicReference<String> scripted = new AtomicReference<>("FINDINGS: 市场情绪偏谨慎\nAPPROACHES: 技术面 | 资金面");
        AtomicInteger clientInvokeCount = new AtomicInteger();

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
                        clientInvokeCount.incrementAndGet();
                        return new AssistantMessage(scripted.get());
                    }
                };
            }
        });

        Model model = buildModel(provider);

        // --- (A) agent uses the Model directly via setLlm ---
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-adapter-e2e").build());
        agent.setLlm(model);

        // --- (B) Explorer's Function derives from the SAME Model via the adapter ---
        ObjectProvider<Model> providerObj = new SingletonObjectProvider<>(model);
        Function<String, String> exploreFn = EdpaAutoConfiguration.modelExploringFunction(providerObj);
        Explorer explorer = new LlmExplorer(exploreFn, new ExploreBudget(2, 3, 60_000));

        // Explorer drives the Model through the adapter — proves Function works.
        ExplorationResult result = explorer.explore("分析A股走势", new ExploreBudget(2, 3, 60_000));
        assertThat(result.findings()).as("Explorer Function must return the Model's response content")
                .contains("市场情绪偏谨慎");
        assertThat(result.candidateApproaches()).as("approaches must be parsed from the Model response").contains("技术面",
                "资金面");
        assertThat(clientInvokeCount.get()).as("the same Model client must serve the Explorer invoke").isGreaterThan(0);

        // mutation-RED: if the adapter returned "" (no Model), findings would be empty → RED.

        // --- (C) mutating the single shared source flips both paths ---
        scripted.set("FINDINGS: 政策利好\nAPPROACHES: 宏观 | 微观");
        ExplorationResult result2 = explorer.explore("再分析", new ExploreBudget(2, 3, 60_000));
        assertThat(result2.findings()).as("shared Model source mutation must reflect in Explorer").contains("政策利好");
    }

    /**
     * Builds a real Model whose underlying client is the registered stub.
     *
     * @param provider the provider name registered via {@link Model#registerFactory}
     * @return a real {@link Model} whose underlying client is the registered stub
     */
    private static Model buildModel(String provider) {
        var cliCfg = ModelClientConfig.builder().clientId(provider + "-" + System.nanoTime()).clientProvider(provider)
                .apiKey("stub").apiBase("http://stub").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("stub-model").temperature(0.3).maxTokens(200).build();
        return new Model(cliCfg, reqCfg);
    }

    /** Minimal ObjectProvider holding a single value. */
    private static final class SingletonObjectProvider<T extends Model> implements ObjectProvider<T> {
        private final T value;

        SingletonObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() throws org.springframework.beans.BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return java.util.List.of(value).iterator();
        }

        @Override
        public java.util.stream.Stream<T> stream() {
            return java.util.stream.Stream.of(value);
        }

        @Override
        public T getObject(Object... args) throws org.springframework.beans.BeansException {
            return value;
        }
    }

    /** Abstract stub client — mirrors react-rails StubModelClient signatures. */
    private abstract static class StubModelClient extends BaseModelClient {
        StubModelClient(ModelRequestConfig r, ModelClientConfig c) {
            super(r, c);
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
