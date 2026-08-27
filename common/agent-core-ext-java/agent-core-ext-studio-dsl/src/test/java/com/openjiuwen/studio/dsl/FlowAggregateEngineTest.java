/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowaggregate.FlowAggregateConfig;
import com.openjiuwen.studio.dsl.flowaggregate.FlowAggregateEngine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link FlowAggregateEngine} Python parity.
 *
 * @since 2026-08-26
 */

class FlowAggregateEngineTest {
    @Test
    void extractUser_emptyUserFieldsFallsBackToFlat() {
        Map<String, Object> user =
                FlowAggregateEngine.extractUser(Map.of("userFields", Map.of(), "a", 1, "b", 2));
        assertThat(user).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void firstNonEmpty_skipsBlankStrings() {
        assertThat(FlowAggregateEngine.firstNonEmpty(List.of("", "x"))).isEqualTo("x");
        assertThat(FlowAggregateEngine.firstNonEmpty(List.of())).isNull();
    }

    @Test
    void groupsMap_listOfDicts() {
        Map<String, List<String>> g =
                FlowAggregateConfig.groupsMap(List.of(Map.of("id", "o1", "value_list", List.of("p", "q"))));
        assertThat(g).containsEntry("o1", List.of("p", "q"));
    }

    @Test
    void invoke_returnsOnlyAggregatedUserFields() {
        FlowAggregateEngine engine =
                new FlowAggregateEngine(
                        "agg",
                        FlowAggregateConfig.fromNodeConfigs(
                                Map.of("groups", Map.of("merged", List.of("x", "y")))));
        Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of("x", "", "y", "ok")), null);
        assertThat(out).containsOnlyKeys("userFields");
        assertThat(out.get("userFields")).isEqualTo(Map.of("merged", "ok"));
    }

    @Test
    void unsupportedModeRaises() {
        FlowAggregateEngine engine =
                new FlowAggregateEngine(
                        "agg",
                        FlowAggregateConfig.fromNodeConfigs(
                                Map.of("mode", "merge-all", "groups", Map.of("x", List.of("a")))));
        assertThatThrownBy(() -> engine.invoke(Map.of("userFields", Map.of("a", 1)), null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("unsupported aggregate mode");
    }
}
