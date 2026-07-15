/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Explorer SPI + records bearing tests.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip ExploreBudget validation → negative values accepted → RED</li>
 *   <li>Strip ExplorationResult defensive copy → mutable list leaks → RED</li>
 * </ul>
 */
class ExplorerSpiTest {
    @Test
    void explorationResult_defensiveCopyCandidateApproaches() {
        List<String> approaches = new java.util.ArrayList<>(List.of("angle-A", "angle-B"));
        ExplorationResult result = new ExplorationResult("findings", approaches);

        approaches.add("angle-C");

        assertThat(result.candidateApproaches()).as("mutation-RED: defensive copy must prevent external mutation")
                .containsExactly("angle-A", "angle-B").doesNotContain("angle-C");
    }

    @Test
    void explorationResult_singleFindingConstructor() {
        ExplorationResult result = new ExplorationResult("just findings");

        assertThat(result.findings()).isEqualTo("just findings");
        assertThat(result.candidateApproaches()).isEmpty();
    }

    @Test
    void explorationResult_nullApproachesDefaultsToEmpty() {
        ExplorationResult result = new ExplorationResult("findings", null);

        assertThat(result.candidateApproaches()).as("null candidateApproaches must default to empty list").isEmpty();
    }

    @Test
    void exploreBudget_defaultValues() {
        assertThat(ExploreBudget.DEFAULT.maxRounds()).isEqualTo(2);
        assertThat(ExploreBudget.DEFAULT.maxSubAgents()).isEqualTo(3);
        assertThat(ExploreBudget.DEFAULT.timeoutMillis()).isEqualTo(60_000);
    }

    @Test
    void exploreBudget_negativeMaxRoundsThrows() {
        assertThatThrownBy(() -> new ExploreBudget(-1, 3, 60_000))
                .as("mutation-RED: validation must reject negative maxRounds")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRounds");
    }

    @Test
    void exploreBudget_negativeMaxSubAgentsThrows() {
        assertThatThrownBy(() -> new ExploreBudget(2, -1, 60_000))
                .as("mutation-RED: validation must reject negative maxSubAgents")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSubAgents");
    }

    @Test
    void exploreBudget_negativeTimeoutThrows() {
        assertThatThrownBy(() -> new ExploreBudget(2, 3, -1))
                .as("mutation-RED: validation must reject negative timeout")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeoutMillis");
    }

    @Test
    void exploreBudget_zeroValuesAccepted() {
        ExploreBudget budget = new ExploreBudget(0, 0, 0);

        assertThat(budget.maxRounds()).isZero();
        assertThat(budget.maxSubAgents()).isZero();
        assertThat(budget.timeoutMillis()).isZero();
    }

    @Test
    void explorer_spiCanBeImplementedAsLambda() {
        Explorer explorer = (userInput, budget) -> new ExplorationResult("Explored: " + userInput,
                List.of("approach-1", "approach-2"));

        ExplorationResult result = explorer.explore("test task", ExploreBudget.DEFAULT);

        assertThat(result.findings()).isEqualTo("Explored: test task");
        assertThat(result.candidateApproaches()).containsExactly("approach-1", "approach-2");
    }
}
