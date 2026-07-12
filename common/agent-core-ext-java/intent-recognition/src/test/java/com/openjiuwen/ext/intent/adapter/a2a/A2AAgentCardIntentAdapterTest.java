/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentCandidateLimits;
import com.openjiuwen.ext.intent.catalog.IntentCatalog;
import com.openjiuwen.ext.intent.catalog.IntentCatalogCompiler;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.junit.jupiter.api.Test;

class A2AAgentCardIntentAdapterTest {
    private static final IntentCandidateLimits DEFAULT_LIMITS = new IntentCandidateLimits(4096, 16384, 32, 16);

    @Test
    void buildsDeterministicCandidatesFromSemanticFieldsOnly() {
        AtomicReference<List<SecurityRequirement>> evaluated = new AtomicReference<>();
        A2AEligibilityPolicy policy = policy((card, requirements) -> {
            evaluated.set(requirements);
            return true;
        }, card -> true, Set.of("urn:required"));
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(policy);
        SecurityRequirement cardRequirement = new SecurityRequirement(Map.of("card-auth", List.of("read")));
        AgentSkill skill = new AgentSkill("track", " Track Order ", "Track an existing order",
                List.of("订单", "物流", "订单"), List.of("where is order 1", "track order 2", "where is order 1"), null, null,
                null);
        AgentCard card = card(List.of(skill), List.of(cardRequirement), "JSONRPC", "1.0", true);

        String targetKey = adapter.targetKey(card);
        List<IntentCandidate> candidates = adapter.candidates(0, card, DEFAULT_LIMITS);

        assertThat(targetKey).matches("[0-9a-f]{64}");
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.candidateId()).isEqualTo(targetKey + ":track");
            assertThat(candidate.document()).isEqualTo("""
                    [Target]
                    Order Agent

                    [Target scope]
                    Order operations

                    [Intent]
                    Track Order

                    [Can handle]
                    Track an existing order

                    [Keywords]
                    物流, 订单

                    [Examples]
                    - where is order 1
                    - track order 2""");
            assertThat(candidate.document()).doesNotContain("agent.example", "provider.example", "signature");
        });
        assertThat(evaluated.get()).containsExactly(cardRequirement);
    }

    @Test
    void skillSecurityOverridesCardSecurityAndModesInheritFromCard() {
        SecurityRequirement skillRequirement = new SecurityRequirement(Map.of("skill-auth", List.of()));
        AtomicReference<List<SecurityRequirement>> evaluated = new AtomicReference<>();
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(policy((card, requirements) -> {
            evaluated.set(requirements);
            return true;
        }, card -> true, Set.of("urn:required")));
        AgentSkill skill = new AgentSkill("track", "Track", "Track", List.of(), null, List.of(), null,
                List.of(skillRequirement));
        AgentCard card = card(List.of(skill), List.of(new SecurityRequirement(Map.of("card-auth", List.of()))),
                "JSONRPC", "1.0", true);

        assertThat(adapter.candidates(0, card, DEFAULT_LIMITS)).hasSize(1);
        assertThat(evaluated.get()).containsExactly(skillRequirement);
    }

    @Test
    void filtersCardsOrSkillsThatFailEligibility() {
        AgentCard valid = card(List.of(new AgentSkill("track", "Track", "Track", List.of(), null, null, null, null)),
                null, "JSONRPC", "1.0", true);

        assertThat(new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> true, card -> false, Set.of("urn:required")))
                .candidates(0, valid, DEFAULT_LIMITS)).isEmpty();
        assertThat(new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> false, card -> true, Set.of("urn:required")))
                .candidates(0, valid, DEFAULT_LIMITS)).isEmpty();
        assertThat(new A2AAgentCardIntentAdapter(policy((card, requirements) -> true, card -> true, Set.of()))
                .candidates(0, valid, DEFAULT_LIMITS)).isEmpty();

        AgentCard wrongInterface = card(valid.skills(), null, "GRPC", "1.0", false);
        assertThat(new A2AAgentCardIntentAdapter(policy((card, requirements) -> true, card -> true, Set.of()))
                .candidates(0, wrongInterface, DEFAULT_LIMITS)).isEmpty();

        AgentSkill wrongMode = new AgentSkill("audio", "Audio", "Audio", List.of(), null, List.of("audio/wav"), null,
                null);
        AgentCard wrongModeCard = card(List.of(wrongMode), null, "JSONRPC", "1.0", false);
        assertThat(new A2AAgentCardIntentAdapter(policy((card, requirements) -> true, card -> true, Set.of()))
                .candidates(0, wrongModeCard, DEFAULT_LIMITS)).isEmpty();
    }

    @Test
    void filtersWhenTrustOrSecurityEvaluationFails() {
        AgentCard card = card(List.of(new AgentSkill("track", "Track", "Track", List.of(), null, null, null, null)),
                null, "JSONRPC", "1.0", false);
        A2AAgentCardIntentAdapter brokenTrust = new A2AAgentCardIntentAdapter(
                policy((value, requirements) -> true, value -> {
                    throw new IllegalStateException("trust unavailable");
                }, Set.of()));
        A2AAgentCardIntentAdapter brokenSecurity = new A2AAgentCardIntentAdapter(policy((value, requirements) -> {
            throw new IllegalStateException("credentials unavailable");
        }, value -> true, Set.of()));

        assertThat(brokenTrust.candidates(0, card, DEFAULT_LIMITS)).isEmpty();
        assertThat(brokenSecurity.candidates(0, card, DEFAULT_LIMITS)).isEmpty();
    }

    @Test
    void filtersDuplicateSkillsAndTextThatExceedsConfiguredLimits() {
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> true, card -> true, Set.of("urn:required")));
        AgentSkill first = new AgentSkill("same", "One", "One", List.of(), null, null, null, null);
        AgentSkill second = new AgentSkill("same", "Two", "Two", List.of(), null, null, null, null);
        AgentCard duplicate = card(List.of(first, second), null, "JSONRPC", "1.0", false);
        AgentCard longSkill = card(List.of(new AgentSkill("long", "12345", "ok", List.of(), null, null, null, null)),
                null, "JSONRPC", "1.0", false);

        assertThat(adapter.candidates(0, duplicate, DEFAULT_LIMITS)).isEmpty();
        assertThat(adapter.candidates(0, longSkill, new IntentCandidateLimits(4, 100, 2, 2))).isEmpty();
    }

    @Test
    void targetKeyChangesWhenOrderedInterfaceIdentityChanges() {
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> true, card -> true, Set.of()));
        AgentCard first = card(List.of(), null, "JSONRPC", "1.0", false);
        AgentCard changed = AgentCard.builder(first)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://other.example", "tenant", "1.0")))
                .build();

        assertThat(adapter.targetKey(first)).isNotEqualTo(adapter.targetKey(changed));
    }

    @Test
    void filtersMalformedCardWithoutAbortingCatalogCompilation() {
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> true, card -> true, Set.of()));
        AgentCard malformed = AgentCard.builder().name(" ").description("description").version("1")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain")).skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://agent.example", null, "1.0")))
                .build();
        IntentRecognizerConfig config = IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("a2a-v1").modelVersion("model-v1").build();

        IntentCatalog<AgentCard> catalog = new IntentCatalogCompiler<AgentCard>(config).compile(List.of(malformed),
                adapter);

        assertThat(catalog.candidates()).isEmpty();
    }

    @Test
    void filtersCardWhenInterfaceFieldsExceedLocalFieldLimit() {
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(
                policy((card, requirements) -> true, card -> true, Set.of()));
        AgentCard card = card(
                List.of(new AgentSkill("skill", "Skill", "Description", List.of(), null, null, null, null)), null,
                "JSONRPC", "1.0", false);
        card = AgentCard.builder(card)
                .supportedInterfaces(
                        List.of(new AgentInterface("JSONRPC", "https://" + "x".repeat(200) + ".example", null, "1.0")))
                .build();

        assertThat(adapter.candidates(0, card, new IntentCandidateLimits(100, 1000, 10, 10))).isEmpty();
    }

    private static A2AEligibilityPolicy policy(A2ASecurityRequirementEvaluator securityEvaluator,
            A2AContentTrustEvaluator contentTrustEvaluator, Set<String> supportedExtensions) {
        return new A2AEligibilityPolicy(Set.of("JSONRPC"), Set.of("1.0"), supportedExtensions, Set.of("text/plain"),
                securityEvaluator, contentTrustEvaluator);
    }

    private static AgentCard card(List<AgentSkill> skills, List<SecurityRequirement> securityRequirements,
            String binding, String protocolVersion, boolean requiredExtension) {
        List<AgentExtension> extensions = requiredExtension
                ? List.of(new AgentExtension("Required", Map.of("mode", "strict"), true, "urn:required"))
                : null;
        return AgentCard.builder().name("Order Agent").description("Order operations")
                .provider(new AgentProvider("Provider", "https://provider.example")).version("1.0.0")
                .documentationUrl("https://docs.example")
                .capabilities(new AgentCapabilities(false, false, false, extensions))
                .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("application/json")).skills(skills)
                .securityRequirements(securityRequirements)
                .supportedInterfaces(
                        List.of(new AgentInterface(binding, "https://agent.example", "tenant", protocolVersion)))
                .signatures(List.of()).build();
    }
}
