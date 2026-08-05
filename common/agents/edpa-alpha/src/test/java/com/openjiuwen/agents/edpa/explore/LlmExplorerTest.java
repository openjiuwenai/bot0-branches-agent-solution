/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * LlmExplorer bearing tests.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip prompt building → LLM receives raw userInput → parse fails → empty findings → RED</li>
 *   <li>Strip parseResponse → raw LLM text returned as-is → candidateApproaches always empty → RED</li>
 *   <li>Strip null/blank guard → NPE on null userInput → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class LlmExplorerTest {
    @Test
    void explore_parsesStructuredResponse() {
        LlmExplorer explorer = new LlmExplorer(prompt -> """
                FINDINGS: 市场情绪偏谨慎，资金面收紧，政策面不确定
                APPROACHES: 技术面分析 | 资金面分析 | 政策面分析
                """);

        ExplorationResult result = explorer.explore("分析A股走势", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("findings must be extracted from FINDINGS: line").contains("市场情绪偏谨慎")
                .contains("资金面收紧");
        assertThat(result.candidateApproaches()).as("approaches must be split by |").containsExactly("技术面分析", "资金面分析",
                "政策面分析");
    }

    @Test
    void explore_multiLineFindingsConcatenated() {
        LlmExplorer explorer = new LlmExplorer(prompt -> """
                FINDINGS: 第一行发现
                第二行补充
                第三行风险
                APPROACHES: 方向A | 方向B
                """);

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("multi-line findings must be concatenated").contains("第一行发现").contains("第二行补充")
                .contains("第三行风险");
        assertThat(result.candidateApproaches()).containsExactly("方向A", "方向B");
    }

    @Test
    void explore_unstructuredResponseUsedAsFindings() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "这是一段没有格式的自由文本回答");

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("unstructured response must be used as findings (honest degradation)")
                .isEqualTo("这是一段没有格式的自由文本回答");
        assertThat(result.candidateApproaches()).as("no APPROACHES line → empty list").isEmpty();
    }

    @Test
    void explore_blankLlmResponseReturnsEmpty() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "");

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("blank LLM response must yield empty findings (no fake data)").isEmpty();
        assertThat(result.candidateApproaches()).isEmpty();
    }

    @Test
    void explore_nullLlmResponseReturnsEmpty() {
        LlmExplorer explorer = new LlmExplorer(prompt -> null);

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void explore_llmThrowsReturnsEmpty() {
        LlmExplorer explorer = new LlmExplorer(prompt -> {
            throw new IllegalStateException("LLM timeout");
        });

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("LLM exception must degrade to empty findings (no crash)").isEmpty();
    }

    @Test
    void explore_nullUserInputReturnsEmpty() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "should not be called");

        ExplorationResult result = explorer.explore(null, ExploreBudget.DEFAULT);

        assertThat(result.findings()).as("null userInput must not invoke LLM").isEmpty();
    }

    @Test
    void explore_blankUserInputReturnsEmpty() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "should not be called");

        ExplorationResult result = explorer.explore("   ", ExploreBudget.DEFAULT);

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void explore_approachesWithExtraSpacesTrimmed() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "FINDINGS: test\nAPPROACHES:  方向A  |  方向B  ");

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.candidateApproaches()).as("approaches must be trimmed").containsExactly("方向A", "方向B");
    }

    @Test
    void explore_emptyApproachesAfterSplitFiltered() {
        LlmExplorer explorer = new LlmExplorer(prompt -> "FINDINGS: test\nAPPROACHES: 方向A | | 方向B |");

        ExplorationResult result = explorer.explore("test", ExploreBudget.DEFAULT);

        assertThat(result.candidateApproaches()).as("empty approach strings must be filtered").containsExactly("方向A",
                "方向B");
    }

    @Test
    void constructorRejectsNullLlm() {
        assertThatThrownBy(() -> new LlmExplorer(null)).as("null llm function must be rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("llm");
    }

    @Test
    void constructorRejectsNullBudget() {
        assertThatThrownBy(() -> new LlmExplorer(prompt -> "ok", null)).as("null budget must be rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
    }

    @Test
    void explore_promptContainsUserInput() {
        LlmExplorer explorer = new LlmExplorer(prompt -> {
            assertThat(prompt).as("LLM prompt must contain the user input").contains("分析A股走势");
            return "FINDINGS: ok";
        });

        explorer.explore("分析A股走势", ExploreBudget.DEFAULT);
    }
}
