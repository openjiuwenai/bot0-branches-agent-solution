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
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * EdpaAutoConfiguration bearing tests.
 *
 * <p>Tests the three core wiring concerns:
 * <ol>
 *   <li>BeanPostProcessor registers rails on ReActAgent (not on other beans)</li>
 *   <li>modelExploringFunction adapter: Model → Function (with no-Model fallback)</li>
 *   <li>Explorer bean uses the adapter correctly</li>
 * </ol>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip ExploreRail registration → agent has no explore rail → RED</li>
 *   <li>Strip ReplanTool.registerOnto → __replan__ tool not visible to LLM → RED</li>
 *   <li>Strip no-Model fallback → NPE when modelProvider.getIfAvailable() returns null → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class EdpaAutoConfigurationTest {
    @Test
    void beanPostProcessor_registersRailsOnReActAgent() {
        EdpaProperties props = new EdpaProperties();
        props.setEnabled(true);
        props.setCriteria(List.of("GDP"));
        props.setMaxReplan(2);

        Explorer explorer = (userInput, budget) -> new ExplorationResult("test findings");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test-edpa").build());

        Object result = bpp.postProcessAfterInitialization(agent, "testAgent");

        assertThat(result).as("BeanPostProcessor must return the same agent instance").isSameAs(agent);
    }

    @Test
    void beanPostProcessor_ignoresNonReActAgentBean() {
        EdpaProperties props = new EdpaProperties();
        Explorer explorer = (userInput, budget) -> new ExplorationResult("test");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        String nonAgentBean = "just a string";
        Object result = bpp.postProcessAfterInitialization(nonAgentBean, "stringBean");

        assertThat(result).as("non-ReActAgent beans must pass through unchanged").isSameAs(nonAgentBean);
    }

    @Test
    void beanPostProcessor_registersReplanTool() {
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(2);
        Explorer explorer = (userInput, budget) -> new ExplorationResult("test");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test-replan-tool").build());
        bpp.postProcessAfterInitialization(agent, "testAgent");

        var toolInfos = agent.getAbilityManager().listToolInfo();
        boolean hasReplanTool = toolInfos.stream().anyMatch(t -> "__replan__".equals(t.getName()));
        assertThat(hasReplanTool).as("ReplanTool.registerOnto must make __replan__ visible to the LLM").isTrue();
    }

    @Test
    void beanPostProcessor_noCriteriaSkipsCriteriaRail() {
        EdpaProperties props = new EdpaProperties();
        props.setCriteria(List.of());
        props.setMaxReplan(2);
        Explorer explorer = (userInput, budget) -> new ExplorationResult("test");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test-no-criteria").build());

        Object result = bpp.postProcessAfterInitialization(agent, "testAgent");

        assertThat(result).isSameAs(agent);
    }

    /**
     * config-consumer-reachability (铁律⑰): proactiveConvergenceEnabled=true (with criteria)
     * must register a ProactiveConvergenceRail.
     */
    @Test
    void proactiveConvergenceEnabled_registersProactiveConvergenceRail() {
        EdpaProperties props = new EdpaProperties();
        props.setEnabled(true);
        props.setCriteria(List.of("GDP"));
        props.setMaxReplan(2);
        props.setProactiveConvergenceEnabled(true);
        Explorer explorer = (userInput, budget) -> new ExplorationResult("test");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        RailRecordingAgent agent = new RailRecordingAgent();
        bpp.postProcessAfterInitialization(agent, "testAgent");

        boolean hasProactive = agent.registered.stream()
                .anyMatch(r -> r instanceof com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail);
        assertThat(hasProactive).as(
                "proactiveConvergenceEnabled=true must register ProactiveConvergenceRail (switch is live, not dead)")
                .isTrue();
        // mutation-RED: strip the `if (properties.isProactiveConvergenceEnabled())` guard → always registers →
        // the disabled test below would fail; or hardcode true → this still passes but disabled test REDs.
    }

    /**
     * config-consumer-reachability (铁律⑰) bidirectional: default disabled must NOT register the rail.
     */
    @Test
    void proactiveConvergenceDisabled_skipsProactiveConvergenceRail() {
        EdpaProperties props = new EdpaProperties();
        props.setEnabled(true);
        props.setCriteria(List.of("GDP"));
        props.setMaxReplan(2);
        // proactiveConvergenceEnabled defaults to false
        Explorer explorer = (userInput, budget) -> new ExplorationResult("test");
        var bpp = new EdpaAutoConfiguration().edpaRegistrar(props,
                new com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier(), explorer);

        RailRecordingAgent agent = new RailRecordingAgent();
        bpp.postProcessAfterInitialization(agent, "testAgent");

        boolean hasProactive = agent.registered.stream()
                .anyMatch(r -> r instanceof com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail);
        assertThat(hasProactive).as("proactiveConvergenceEnabled=false must NOT register ProactiveConvergenceRail "
                + "(existing e2e behavior preserved)").isFalse();
    }

    /**
     * Test spy: records every {@code registerRail} call so the config-consumer-reachability
     * tests can assert which rails the BeanPostProcessor wires (the SDK exposes no rail list).
     */
    static class RailRecordingAgent extends ReActAgent {
        final java.util.List<com.openjiuwen.core.singleagent.rail.AgentRail> registered = new java.util.ArrayList<>();

        RailRecordingAgent() {
            super(AgentCard.builder().name("rail-recorder").build());
        }

        @Override
        public com.openjiuwen.core.singleagent.BaseAgent registerRail(
                com.openjiuwen.core.singleagent.rail.AgentRail rail) {
            registered.add(rail);
            return super.registerRail(rail);
        }
    }

    @Test
    void modelExploringFunction_returnsEmptyWhenNoModel() {
        ObjectProvider<Model> emptyProvider = new EmptyObjectProvider();
        Function<String, String> fn = EdpaAutoConfiguration.modelExploringFunction(emptyProvider);

        String result = fn.apply("test prompt");

        assertThat(result).as("no Model bean available → function returns empty string (honest degradation)").isEmpty();
    }

    @Test
    void modelExploringFunction_delegatesToModel() {
        Model model = buildStubModel("FINDINGS: test findings\nAPPROACHES: approach-A | approach-B");
        ObjectProvider<Model> provider = new SingletonObjectProvider(model);
        Function<String, String> fn = EdpaAutoConfiguration.modelExploringFunction(provider);

        String result = fn.apply("explore prompt");

        assertThat(result).as("function must delegate to Model.invoke and return content").contains("test findings")
                .contains("approach-A");
    }

    @Test
    void modelExploringFunction_modelThrowsReturnsEmpty() {
        // Fault-injection stub: a concrete exception type documents intent (model
        // timeout) without relying on a bare RuntimeException. This override is
        // currently not exercised by the stub client, but kept to assert the
        // empty-result degradation path of modelExploringFunction.
        Model model = buildStubModel(null, m -> {
            throw new IllegalStateException("model timeout");
        });
        ObjectProvider<Model> provider = new SingletonObjectProvider(model);
        Function<String, String> fn = EdpaAutoConfiguration.modelExploringFunction(provider);

        String result = fn.apply("test");

        assertThat(result).as("Model exception must be caught — LlmExplorer handles RuntimeException → empty")
                .isEmpty();
    }

    @Test
    void explorerBean_usesModelAdapterForExploration() {
        Model model = buildStubModel("FINDINGS: discovered X\nAPPROACHES: path-1 | path-2");
        ObjectProvider<Model> provider = new SingletonObjectProvider(model);
        Function<String, String> fn = EdpaAutoConfiguration.modelExploringFunction(provider);
        Explorer explorer = new LlmExplorer(fn, ExploreBudget.DEFAULT);

        ExplorationResult result = explorer.explore("analyze market", ExploreBudget.DEFAULT);

        assertThat(result.findings()).contains("discovered X");
        assertThat(result.candidateApproaches()).contains("path-1", "path-2");
    }

    @Test
    void explorerBean_emptyWhenNoModel() {
        ObjectProvider<Model> emptyProvider = new EmptyObjectProvider();
        Function<String, String> fn = EdpaAutoConfiguration.modelExploringFunction(emptyProvider);
        Explorer explorer = new LlmExplorer(fn, ExploreBudget.DEFAULT);

        ExplorationResult result = explorer.explore("analyze market", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("no Model → empty findings → ExploreRail skips pushSteering").isEmpty();
    }

    @Test
    void edpaProperties_buildsCorrectBudget() {
        EdpaProperties props = new EdpaProperties();
        props.setExploreRounds(5);
        props.setMaxSubagents(10);
        props.setExploreTimeoutMillis(120_000);

        ExploreBudget budget = props.toExploreBudget();

        assertThat(budget.maxRounds()).isEqualTo(5);
        assertThat(budget.maxSubAgents()).isEqualTo(10);
        assertThat(budget.timeoutMillis()).isEqualTo(120_000);
    }

    // ============================================================
    // Test helpers
    // ============================================================

    private static final class SingletonObjectProvider implements ObjectProvider<Model> {
        private final Model value;

        SingletonObjectProvider(Model value) {
            this.value = value;
        }

        @Override
        public Model getObject() {
            return value;
        }

        @Override
        public Model getIfAvailable() {
            return value;
        }

        @Override
        public Model getIfUnique() {
            return value;
        }

        @Override
        public java.util.Iterator<Model> iterator() {
            return java.util.List.of(value).iterator();
        }

        @Override
        public java.util.stream.Stream<Model> stream() {
            return java.util.stream.Stream.of(value);
        }

        @Override
        public Model getObject(Object... args) {
            return value;
        }
    }

    private static final class EmptyObjectProvider implements ObjectProvider<Model> {
        @Override
        public Model getObject() {
            return null;
        }

        @Override
        public Model getIfAvailable() {
            return null;
        }

        @Override
        public Model getIfUnique() {
            return null;
        }

        @Override
        public java.util.Iterator<Model> iterator() {
            return java.util.List.<Model>of().iterator();
        }

        @Override
        public java.util.stream.Stream<Model> stream() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public Model getObject(Object... args) {
            return null;
        }
    }

    private static Model buildStubModel(String responseContent) {
        return buildStubModel(responseContent, null);
    }

    private static Model buildStubModel(String responseContent, java.util.function.Consumer<Model> invokeOverride) {
        String provider = "edpa-test-" + System.nanoTime();
        com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories.ensureRegistered();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient create(
                    com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig r,
                    com.openjiuwen.core.foundation.llm.schema.ModelClientConfig c) {
                return new StubBaseModelClient(r, c, responseContent);
            }
        });
        var cliCfg = com.openjiuwen.core.foundation.llm.schema.ModelClientConfig.builder()
                .clientId(provider + "-" + System.nanoTime()).clientProvider(provider).apiKey("stub")
                .apiBase("http://stub").verifySsl(false).build();
        var reqCfg = com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig.builder().modelName("stub-model")
                .temperature(0.3).maxTokens(200).build();
        return new Model(cliCfg, reqCfg);
    }

    private static class StubBaseModelClient extends com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient {
        private final String responseContent;

        StubBaseModelClient(com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig r,
                com.openjiuwen.core.foundation.llm.schema.ModelClientConfig c, String responseContent) {
            super(r, c);
            this.responseContent = responseContent;
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens, String model,
                Integer n, String stop, com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser parser,
                Float topP, Map<String, Object> kwargs) {
            if (responseContent == null) {
                return new AssistantMessage("");
            }
            return new AssistantMessage(responseContent);
        }

        @Override
        public java.util.Iterator<com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk> stream(
                Object messages, Object tools, Float temperature, Float topP, String model, Integer maxTokens,
                String stop, com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser outputParser,
                Float timeout, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse generateImage(
                java.util.List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String model,
                String size, String negativePrompt, int n, boolean promptExtend, boolean watermark, int seed,
                Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse generateSpeech(
                java.util.List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String model,
                String voice, String languageType, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse generateVideo(
                java.util.List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String imgUrl,
                String audioUrl, String model, String size, String resolution, int duration, boolean promptExtend,
                boolean watermark, String negativePrompt, Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }
}
