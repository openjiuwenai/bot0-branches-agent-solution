/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereview;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.workflow.Workflow;

import org.junit.jupiter.api.Test;

/**
 * Structural smoke test for the Expense Review Workflow DAG.
 *
 * <p>Verifies the DAG builds and its card metadata is complete WITHOUT executing
 * the workflow or calling any LLM. No API key needed; runs in all environments.
 */
class ExpenseReviewDagStructureTest {
    private static final String MODEL_PROVIDER = "structure-test";
    private static final String API_KEY = "noop";
    private static final String API_BASE = "http://localhost";
    private static final String MODEL_NAME = "noop";

    @Test
    void shouldBuildDagWithCompleteCardMetadata() {
        Workflow wf = ExpenseReviewConfiguration.buildExpenseReviewWorkflow(
                MODEL_PROVIDER, API_KEY, API_BASE, MODEL_NAME, false);

        assertThat(wf).as("workflow should not be null").isNotNull();
        assertThat(wf.getCard().getId()).isEqualTo("expense-review");
        assertThat(wf.getCard().getName()).isEqualTo("费用报销审核");
        assertThat(wf.getCard().getVersion()).isEqualTo("1.0");
        assertThat(wf.getCard().getDescription()).isNotBlank();
    }
}
