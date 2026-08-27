/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Node-level assertions from {@code test_case_aggregation_common_02} / {@code _08}.
 *
 * @since 2026-08-25
 */

class WorkflowNodeAggregationCommonCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    /**
     * common_02: 1 group 1 param — first-non-null(param1).
     *
     * @since 0.1.0
     */
    @Test
    void common02_singleGroup_firstNonNull() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "node_aggregation",
                        "jiuwen.aggregate",
                        Map.of(
                                "mode",
                                "first-non-null",
                                "groups",
                                Map.of("group1", List.of("group1_1")))),
                NodeBuildContext.defaults("wf_agg02"));
        assertThat(uf(exec.invoke(Map.of("userFields", Map.of("group1_1", "嗨")), null, null)))
                .containsEntry("group1", "嗨");
    }

    /**
     * common_08: 2 groups × 2 params — both groups pick first non-null.
     *
     * @since 0.1.0
     */
    @Test
    void common08_twoGroups_firstNonNullBothAbc() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "aggregate",
                        "jiuwen.aggregate",
                        Map.of(
                                "mode",
                                "first-non-null",
                                "groups",
                                Map.of(
                                        "group1",
                                        List.of("group1_1", "group1_2"),
                                        "group2",
                                        List.of("group2_1", "group2_2")))),
                NodeBuildContext.defaults("wf_agg08"));
        Map<String, Object> fields = uf(exec.invoke(
                Map.of(
                        "userFields",
                        Map.of(
                                "group1_1",
                                "abc",
                                "group1_2",
                                "123",
                                "group2_1",
                                "abc",
                                "group2_2",
                                "123")),
                null,
                null));
        assertThat(fields).containsEntry("group1", "abc").containsEntry("group2", "abc");
    }

    /**
     * common_08 fallback: empty first param → second.
     *
     * @since 0.1.0
     */
    @Test
    void common08_fallbackToSecondParam() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "aggregate",
                        "jiuwen.aggregate",
                        Map.of(
                                "mode",
                                "first-non-null",
                                "groups",
                                Map.of(
                                        "group1",
                                        List.of("group1_1", "group1_2"),
                                        "group2",
                                        List.of("group2_1", "group2_2")))),
                NodeBuildContext.defaults("wf_agg08_fb"));
        Map<String, Object> fields = uf(exec.invoke(
                Map.of(
                        "userFields",
                        Map.of(
                                "group1_1",
                                "",
                                "group1_2",
                                "123",
                                "group2_1",
                                "",
                                "group2_2",
                                "123")),
                null,
                null));
        assertThat(fields).containsEntry("group1", "123").containsEntry("group2", "123");
    }
}
