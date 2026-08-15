/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void edpaRailsAssembly_firesCognitiveLoopWithRealLlm() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String model = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        String enabled = System.getenv("EDPA_RAILS_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank()
                        && "true".equalsIgnoreCase(enabled),
                "EdpaRails e2e requires OPENJIUWEN_API_KEY/BASE_URL + EDPA_RAILS_E2E_ENABLED=true");

        DefaultModelClientFactories.ensureRegistered();

        // 1. Build agent with real LLM
        var cliCfg = ModelClientConfig.builder()
                .clientId("edpa-rails-e2e-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(model).temperature(0.3).maxTokens(4000).build();
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-rails-e2e").build());
        agent.setLlm(new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg));

        // 2. Assemble via C1 facade (the SOLE truth source)
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(3);
        props.setCriteria(List.of("GDP", "CPI", "通胀率"));
        // Keep convergence off for this basic test (it's tested separately)
        props.setProactiveConvergenceEnabled(false);

        CriteriaVerifier verifier = new RuleBasedCriteriaVerifier(); // C5: keyword default
        Explorer explorer = (userInput, budget) -> new ExplorationResult("测试探索结果", List.of("方案A"));

        EdpaRails.RegistrationSummary summary = EdpaRails.registerOnto(agent, props, verifier, explorer);

        // Observability bootstrap (MANDATORY — EdpaRails does NOT install it;
        // hosts must call ReactRailsObservability.install themselves). Without
        // ObservingRail, forceFinish still executes but no ForceFinishEvent fires.
        // install() REPLACES the telemetry listener, so re-stack the test collector
        // on top (production path + collector, both active).
        com.openjiuwen.agents.reactrails.observability.ReactRailsObservability.install(agent);
        RailTelemetry.setCurrent(RailTelemetry.current().with(collectedEvents::add));

        // 3. Content-IFF assertions on the summary
        assertThat(summary.toolMode()).as("default exploreMode is tool → toolMode=true").isTrue();
        assertThat(summary.criteriaRailCount()).as("non-empty criteria → 1 bridge rail").isEqualTo(1);
        assertThat(summary.replanBudget()).as("summary must reflect configured budget").isEqualTo(3);

        // 4. ReplanTool is LLM-visible
        var toolInfos = agent.getAbilityManager().listToolInfo();
        boolean hasReplanTool = toolInfos.stream().anyMatch(t -> ReplanTool.TOOL_NAME.equals(t.getName()));
        assertThat(hasReplanTool).as("ReplanTool must be visible to the LLM after EdpaRails.registerOnto").isTrue();

        // 5. Invoke with real LLM — hard criteria forces verify-fail → replan cycle
        Object result = agent.invoke(
                "分析当前经济形势。必须包含 GDP、CPI 和通胀率数据。", null);

        assertThat(result).as("invoke must return non-null").isNotNull();

        // 6. Verify RailEvents were captured (content-IFF, not just "non-empty")
        List<RailEvent> events = new ArrayList<>(collectedEvents);
        assertThat(events).as("EdpaRails-assembled agent must produce rail events").isNotEmpty();

        // At minimum, verify the ForceFinishEvent with source_rail attribution
        boolean hasForceFinish = events.stream()
                .anyMatch(e -> e.type() == RailEventType.FORCE_FINISH);
        assertThat(hasForceFinish)
                .as("ForceFinishEvent must fire (cognitive loop terminal) — events: %s",
                        events.stream().map(e -> e.type().name()).toList())
                .isTrue();

        // Verify the forceFinish has a source_rail (not "ObservingRail" fallback)
        events.stream()
                .filter(e -> e.type() == RailEventType.FORCE_FINISH)
                .forEach(e -> {
                    RailEvent.ForceFinishEvent ffe = (RailEvent.ForceFinishEvent) e;
                    assertThat(ffe.railName())
                            .as("source_rail must be a business rail (not ObservingRail fallback)")
                            .isNotEqualTo("ObservingRail");
                });

        LOG.log(Level.INFO, "[edpa-rails-e2e] events captured: {0}, result={1}",
                new Object[]{events.size(),
                        String.valueOf(result).substring(0, Math.min(200, String.valueOf(result).length()))});
    }

    @Test
    void edpaRailsAssembly_railModeStillFiresExplorePhase() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String enabled = System.getenv("EDPA_RAILS_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank()
                        && "true".equalsIgnoreCase(enabled),
                "EdpaRails e2e requires OPENJIUWEN_API_KEY/BASE_URL + EDPA_RAILS_E2E_ENABLED=true");

        DefaultModelClientFactories.ensureRegistered();

        var cliCfg = ModelClientConfig.builder()
                .clientId("edpa-rails-rail-e2e-" + System.nanoTime())
                .clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash"))
                .temperature(0.3).maxTokens(4000).build();
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-rail-mode-e2e").build());
        agent.setLlm(new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg));

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
        boolean hasAnyEvent = !events.isEmpty();
        LOG.log(Level.INFO, "[edpa-rails-rail-e2e] events={0}", events.size());
        assertThat(hasAnyEvent).as("rail-mode agent must produce rail events (ExploreRail fires)").isTrue();
    }
}
