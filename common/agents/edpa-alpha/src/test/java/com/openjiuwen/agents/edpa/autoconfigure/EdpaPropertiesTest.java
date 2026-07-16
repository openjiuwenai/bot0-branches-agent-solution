/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * EdpaProperties bearing tests.
 *
 * @since 2026-07
 */
class EdpaPropertiesTest {
    @Test
    void defaults_areSensible() {
        EdpaProperties props = new EdpaProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getExploreRounds()).isEqualTo(2);
        assertThat(props.getMaxSubagents()).isEqualTo(3);
        assertThat(props.getExploreTimeoutMillis()).isEqualTo(60_000);
        assertThat(props.getCriteria()).isEmpty();
        assertThat(props.getMaxReplan()).isEqualTo(2);
    }

    @Test
    void toExploreBudget_mapsProperties() {
        EdpaProperties props = new EdpaProperties();
        props.setExploreRounds(5);
        props.setMaxSubagents(10);
        props.setExploreTimeoutMillis(120_000);

        ExploreBudget budget = props.toExploreBudget();

        assertThat(budget.maxRounds()).isEqualTo(5);
        assertThat(budget.maxSubAgents()).isEqualTo(10);
        assertThat(budget.timeoutMillis()).isEqualTo(120_000);
    }

    @Test
    void criteria_canBeSetAndRetrieved() {
        EdpaProperties props = new EdpaProperties();
        props.setCriteria(List.of("GDP", "CPI", "通胀率"));

        assertThat(props.getCriteria()).containsExactly("GDP", "CPI", "通胀率");
    }
}
