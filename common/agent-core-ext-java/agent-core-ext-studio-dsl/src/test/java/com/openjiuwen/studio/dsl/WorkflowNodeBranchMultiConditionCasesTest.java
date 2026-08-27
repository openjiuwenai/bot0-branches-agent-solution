/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_branch_multi_condition_logic_and} — ConditionEvaluator + BranchNodeHandler.
 *
 * @since 2026-08-25
 */

class WorkflowNodeBranchMultiConditionCasesTest {
    private static final String BRANCH2_AND =
            "(length(${query}) > 1) "
                    + "&& (length(${query}) >= 1) "
                    + "&& (length(${query}) < 100) "
                    + "&& (length(${query}) <= 100) "
                    + "&& (${query} == '你好') "
                    + "&& (${query} != '100') "
                    + "&& ('你' in ${query}) "
                    + "&& ('99' not_in ${query}) "
                    + "&& (is_not_empty(${query})) "
                    + "&& (is_empty(${code_output}))";

    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeBranch(ComponentExecutable exec, Map<String, Object> userFields) {
        return (Map<String, Object>) exec.invoke(Map.of("userFields", userFields), null, null);
    }

    private ComponentExecutable branch(String ifExpr) {
        return registry.create(
                AssembledNode.of(
                        "branch",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of("branchId", "if", "condition", ifExpr),
                                        Map.of("branchId", "default", "isDefault", true)))),
                NodeBuildContext.defaults("wf_br"));
    }

    @Nested
    class MultiConditionAndPaths {
        @Test
        void pathA_allAndConditionsPass() {
            ComponentExecutable exec = branch(BRANCH2_AND);
            Map<String, Object> out = invokeBranch(exec, Map.of("query", "你好", "code_output", ""));
            assertThat(out.get("branchId")).isEqualTo("if");
        }

        @Test
        void pathB_queryNotTarget_goesDefault() {
            ComponentExecutable exec = branch(BRANCH2_AND);
            Map<String, Object> out = invokeBranch(exec, Map.of("query", "hello", "code_output", ""));
            assertThat(out.get("branchId")).isEqualTo("default");
        }

        @Test
        void pathD_emptyQuery_goesDefault() {
            ComponentExecutable exec = branch("(length(${query}) > 0)");
            Map<String, Object> out = invokeBranch(exec, Map.of("query", ""));
            assertThat(out.get("branchId")).isEqualTo("default");
        }

        @Test
        void branch1_nonEmptyQuery_selectsIf() {
            ComponentExecutable exec = branch("(length(${query}) > 0)");
            assertThat(invokeBranch(exec, Map.of("query", "你好")).get("branchId")).isEqualTo("if");
        }
    }

    @Nested
    class ExpressionOperators {
        @Test
        void lengthGt() {
            assertThat(ConditionEvaluator.matches("(length(${query}) > 0)", Map.of("query", "test"))).isTrue();
            assertThat(ConditionEvaluator.matches("(length(${query}) > 0)", Map.of("query", ""))).isFalse();
        }

        @Test
        void inOperator() {
            assertThat(ConditionEvaluator.matches("('你' in ${query})", Map.of("query", "你好世界"))).isTrue();
            assertThat(ConditionEvaluator.matches("('你' in ${query})", Map.of("query", "hello"))).isFalse();
        }

        @Test
        void notInOperator() {
            assertThat(ConditionEvaluator.matches("('99' not_in ${query})", Map.of("query", "hello"))).isTrue();
            assertThat(ConditionEvaluator.matches("('99' not_in ${query})", Map.of("query", "test99value")))
                    .isFalse();
        }

        @Test
        void isEmptyAndNotEmpty() {
            assertThat(ConditionEvaluator.matches("(is_empty(${query}))", Map.of("query", ""))).isTrue();
            assertThat(ConditionEvaluator.matches("(is_not_empty(${query}))", Map.of("query", "hello"))).isTrue();
            assertThat(ConditionEvaluator.matches("(is_not_empty(${query}))", Map.of("query", ""))).isFalse();
        }

        @Test
        void andCombined() {
            String shortOk =
                    "(length(${query}) > 0) && (length(${query}) < 10)";
            assertThat(ConditionEvaluator.matches(shortOk, Map.of("query", "hello"))).isTrue();
            String tooStrict =
                    "(length(${query}) > 0) && (length(${query}) < 3)";
            assertThat(ConditionEvaluator.matches(tooStrict, Map.of("query", "hello"))).isFalse();
        }

        @Test
        void mapOperators_gteAndIn() {
            assertThat(ConditionEvaluator.matches(
                            Map.of("variable", "n", "operator", "gte", "value", 3), Map.of("n", 3)))
                    .isTrue();
            assertThat(ConditionEvaluator.matches(
                            Map.of("variable", "q", "operator", "in", "value", "你"), Map.of("q", "你好")))
                    .isTrue();
        }
    }
}
