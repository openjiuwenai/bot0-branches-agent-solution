/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentAgentResolverTest {
    @Test
    void prefersWorkflowReturnedAgentId() {
        VersatileProperties props = new VersatileProperties();
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        Optional<String> resolved = resolver.resolve("intent_L1_hotel", "agent_card_workflow");

        assertThat(resolved).hasValue("agent_card_workflow");
    }

    @Test
    void firstStrategyPicksFirstCandidate() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of(
                "intent_L1_flight", List.of(candidate("a", 0), candidate("b", 0))
        ));
        props.setIntentAgentMappingStrategy(VersatileProperties.IntentAgentMappingStrategy.FIRST);
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("a");
        assertThat(resolver.resolve("intent_L1_flight", "")).hasValue("a");
    }

    @Test
    void priorityStrategyPicksLowestPriority() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of(
                "intent_L1_flight", List.of(candidate("a", 5), candidate("b", 1))
        ));
        props.setIntentAgentMappingStrategy(VersatileProperties.IntentAgentMappingStrategy.PRIORITY);
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("b");
    }

    @Test
    void roundRobinStrategyRotatesPerIntentId() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of(
                "intent_L1_flight", List.of(candidate("a", 0), candidate("b", 0), candidate("c", 0))
        ));
        props.setIntentAgentMappingStrategy(VersatileProperties.IntentAgentMappingStrategy.ROUND_ROBIN);
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("a");
        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("b");
        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("c");
        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("a");
    }

    @Test
    void unmappedIntentIdThrows() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of());
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThatThrownBy(() -> resolver.resolve("intent_unknown", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSATILE_INTENT_AGENT_ID_UNMAPPED")
                .hasMessageContaining("intent_unknown");
    }

    @Test
    void emptyCandidateListThrows() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of("intent_L1_hotel", List.of()));
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThatThrownBy(() -> resolver.resolve("intent_L1_hotel", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSATILE_INTENT_AGENT_ID_UNMAPPED");
    }

    @Test
    void roundRobinCursorIsIsolatedPerIntentId() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of(
                "intent_A", List.of(candidate("a1", 0), candidate("a2", 0)),
                "intent_B", List.of(candidate("b1", 0), candidate("b2", 0))
        ));
        props.setIntentAgentMappingStrategy(VersatileProperties.IntentAgentMappingStrategy.ROUND_ROBIN);
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThat(resolver.resolve("intent_A", null)).hasValue("a1");
        assertThat(resolver.resolve("intent_B", null)).hasValue("b1");
        assertThat(resolver.resolve("intent_A", null)).hasValue("a2");
        assertThat(resolver.resolve("intent_B", null)).hasValue("b2");
    }

    @Test
    void firstStrategySkipsCandidatesWithBlankAgentCard() {
        VersatileProperties props = new VersatileProperties();
        props.setIntentAgentMapping(Map.of(
                "intent_L1_flight", List.of(candidate(null, 0), candidate("", 0), candidate("valid", 0))
        ));
        props.setIntentAgentMappingStrategy(VersatileProperties.IntentAgentMappingStrategy.FIRST);
        IntentAgentResolver resolver = new IntentAgentResolver(props);

        assertThat(resolver.resolve("intent_L1_flight", null)).hasValue("valid");
    }

    private static VersatileProperties.MappingCandidate candidate(String agentCard, int priority) {
        VersatileProperties.MappingCandidate c = new VersatileProperties.MappingCandidate();
        c.setAgentCard(agentCard);
        c.setPriority(priority);
        return c;
    }
}
