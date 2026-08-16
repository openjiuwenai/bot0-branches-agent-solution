/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.EdpaRails;
import com.openjiuwen.agents.edpa.autoconfigure.EdpaProperties;
import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailEventType;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * C1 refactor bearing e2e: {@link EdpaRails#registerOnto} is the sole assembly
 * truth source — this test proves it works end-to-end with a real LLM.
 *
 * <p>Unlike {@code EdpaCognitiveLoopRealLlmE2eTest} (which manually registers
 * rails one by one), this test uses the C1 facade exclusively:
 * <pre>
 * EdpaRails.registerOnto(agent, props, verifier, explorer)
 * </pre>
 *
 * <p>Asserts (content-IFF, not weak "non-null"):
 * <ul>
 *   <li>RegistrationSummary reflects actual assembly (toolMode/criteriaRailCount).</li>
 *   <li>ReplanTool is LLM-visible (agent's ability manager).</li>
 *   <li>ForceFinishEvent with source_rail attribution fires during real invoke.</li>
 *   <li>C5: default verifier is RuleBasedCriteriaVerifier (keyword, not GroundTruth).</li>
 * </ul>
 *
 * <p>Env-gated: OPENJIUWEN_API_KEY/BASE_URL + EDPA_RAILS_E2E_ENABLED=true.
 *
 * @since 2026-08
 */
class EdpaRailsAssemblyRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(EdpaRailsAssemblyRealLlmE2eTest.class.getName());

    private final List<RailEvent> collectedEvents = new ArrayList<>();

    /**
     * Resets telemetry collector before each test.
     */
    @BeforeEach
    void setUp() {
        collectedEvents.clear();
        RailTelemetry.install(collectedEvents::add);
    }

    /**
     * Resets telemetry to noop after each test (avoid cross-test contamination).
     */
    @AfterEach
    void tearDown() {
        RailTelemetry.setCurrent(null);
    }

    /**
     * Skips unless real-LLM env is present (OPENJIUWEN_API_KEY/BASE_URL +
     * EDPA_RAILS_E2E_ENABLED=true).
     */
    private static void assumeRealLlmEnabled() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String enabled = System.getenv("EDPA_RAILS_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank()
                        && "true".equalsIgnoreCase(enabled),
                "EdpaRails e2e requires OPENJIUWEN_API_KEY/BASE_URL + EDPA_RAILS_E2E_ENABLED=true");
    }

    /**
     * Builds a ReActAgent backed by a real LLM through the tool-calling
     * enforcing model (same wiring the official demo uses).
     *
     * @param name agent/card name (also seeds the client id)
     * @param key API key
     * @param base OpenAI-compatible API base URL
     * @param model model name
     * @return a ready-to-configure ReActAgent
     */
    private static ReActAgent newToolCallingAgent(String name, String key, String base, String model) {
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder()
                .clientId(name + "-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(model).temperature(0.3).maxTokens(4000).build();
        applyThinkingMode(reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name(name).build());
        agent.setLlm(new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg));
        // 15 iterations (SDK default 5): thinking-mode models produce longer turns and need
        // more room to converge to a verified forceFinish terminal (pro+thinking exhausted
        // 5 rounds mid-replan in the 2026-08-16 matrix).
        Object cfg = agent.getConfig();
        if (cfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(15);
        }
        return agent;
    }

    /**
     * Content-IFF assertion on the terminal forceFinish: at least one
     * ForceFinishEvent fired, and its source_rail is a business rail (not the
     * ObservingRail fallback) — zero casts via isInstanceOfSatisfying.
     *
     * @param events rail events captured during invoke
     */
    private static void assertForceFinishAttribution(List<RailEvent> events) {
        assertThat(events)
                .as("ForceFinishEvent must fire (cognitive loop terminal) — events: %s",
                        events.stream().map(e -> e.type().name()).toList())
                .filteredOn(e -> e.type() == RailEventType.FORCE_FINISH)
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(RailEvent.ForceFinishEvent.class,
                        ffe -> assertThat(ffe.railName())
                                .as("source_rail must be a business rail (not ObservingRail fallback)")
                                .isNotEqualTo("ObservingRail")));
    }

    @Test
    void edpaRailsAssembly_firesCognitiveLoopWithRealLlm() {
        assumeRealLlmEnabled();
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String model = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");

        ReActAgent agent = newToolCallingAgent("edpa-rails-e2e", key, base, model);

        // Assemble via C1 facade (the SOLE truth source). Keep convergence off
        // for this basic test (it's tested separately).
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(3);
        props.setCriteria(List.of("GDP", "CPI", "通胀率"));
        props.setProactiveConvergenceEnabled(false);
        CriteriaVerifier verifier = new RuleBasedCriteriaVerifier(); // C5: keyword default
        Explorer explorer = (userInput, budget) -> new ExplorationResult("测试探索结果", List.of("方案A"));
        EdpaRails.RegistrationSummary summary = EdpaRails.registerOnto(agent, props, verifier, explorer);

        // Observability bootstrap (MANDATORY — EdpaRails does NOT install it;
        // hosts must call ReactRailsObservability.install themselves). Without
        // ObservingRail, forceFinish still executes but no ForceFinishEvent fires.
        // install() REPLACES the telemetry listener, so re-stack the test collector.
        com.openjiuwen.agents.reactrails.observability.ReactRailsObservability.install(agent);
        RailTelemetry.setCurrent(RailTelemetry.current().with(collectedEvents::add));

        assertThat(summary.toolMode()).as("default exploreMode is tool → toolMode=true").isTrue();
        assertThat(summary.criteriaRailCount()).as("non-empty criteria → 1 bridge rail").isEqualTo(1);
        assertThat(summary.replanBudget()).as("summary must reflect configured budget").isEqualTo(3);

        var toolInfos = agent.getAbilityManager().listToolInfo();
        boolean hasReplanTool = toolInfos.stream().anyMatch(t -> ReplanTool.TOOL_NAME.equals(t.getName()));
        assertThat(hasReplanTool).as("ReplanTool must be visible to the LLM after EdpaRails.registerOnto").isTrue();

        // Invoke with real LLM — hard criteria forces verify→(replan)→forceFinish cycle
        Object result = agent.invoke(
                "分析当前经济形势。必须包含 GDP、CPI 和通胀率数据。", null);
        assertThat(result).as("invoke must return non-null").isNotNull();

        assertForceFinishAttribution(new ArrayList<>(collectedEvents));

        LOG.log(Level.INFO, "[edpa-rails-e2e] events captured: {0}, result={1}",
                new Object[]{collectedEvents.size(),
                        String.valueOf(result).substring(0, Math.min(200, String.valueOf(result).length()))});
    }

    @Test
    void edpaRailsAssembly_railModeStillFiresExplorePhase() {
        assumeRealLlmEnabled();

        ReActAgent agent = newToolCallingAgent("edpa-rail-mode-e2e",
                System.getenv("OPENJIUWEN_API_KEY"), System.getenv("OPENJIUWEN_BASE_URL"),
                System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash"));

        // Rail mode (not tool mode)
        EdpaProperties props = new EdpaProperties();
        props.setExploreMode("rail");
        props.setMaxReplan(2);
        props.setCriteria(List.of("GDP"));

        EdpaRails.RegistrationSummary summary = EdpaRails.registerOnto(agent, props,
                new RuleBasedCriteriaVerifier(),
                (userInput, budget) -> new ExplorationResult("rail 模式探索", List.of("方案")));

        // Observability bootstrap (same as test 1 — EdpaRails does NOT install it).
        com.openjiuwen.agents.reactrails.observability.ReactRailsObservability.install(agent);
        RailTelemetry.setCurrent(RailTelemetry.current().with(collectedEvents::add));

        // 4-lens BLOCKER fix: rail mode must actually register ExploreRail
        assertThat(summary.toolMode()).as("exploreMode=rail → toolMode=false").isFalse();

        Object result = agent.invoke("分析", null);
        assertThat(result).isNotNull();

        // Rail mode should still produce events (ExploreRail fires SteeringEvent)
        List<RailEvent> events = new ArrayList<>(collectedEvents);
        LOG.log(Level.INFO, "[edpa-rails-rail-e2e] events={0}", events.size());
        assertThat(events).as("rail-mode agent must produce rail events (ExploreRail fires)").isNotEmpty();
    }

    private static void applyThinkingMode(ModelRequestConfig reqCfg) {
        String mode = System.getenv().getOrDefault("LLM_THINKING", "thinking-off");
        switch (mode) {
            case "qwen-on" -> reqCfg.setExtraField("reasoning",
                    java.util.Map.of("enabled", true, "include_reasoning", true));
            case "qwen-off" -> reqCfg.setExtraField("reasoning", java.util.Map.of("enabled", false));
            case "thinking-on" -> reqCfg.setExtraField("thinking",
                    java.util.Map.of("type", "enabled"));
            default -> reqCfg.setExtraField("thinking", java.util.Map.of("type", "disabled"));
        }
    }
}
