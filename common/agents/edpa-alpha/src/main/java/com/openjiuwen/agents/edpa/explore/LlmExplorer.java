/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * LLM-backed Explorer — uses an LLM to actively explore the problem space
 * before planning.
 *
 * <p>The LLM is injected as a {@link Function}<{@code String}, {@code String}>>
 * (prompt → response), keeping this class free of SDK Model coupling. Production
 * wiring provides a function that delegates to {@code Model.invoke()}; tests
 * provide a canned-response function.
 *
 * <p><b>Exploration prompt strategy</b>: asks the LLM to analyze the problem from
 * multiple angles, identify unknowns, and propose candidate approaches. The
 * response is parsed into {@link ExplorationResult} (findings + approaches).
 *
 * <p><b>Honest degradation</b>: if the LLM returns blank or unparseable output,
 * returns an empty {@link ExplorationResult} (no fake findings). The caller
 * ({@link ExploreRail}) checks for empty findings and skips pushSteering.
 *
 * @since 2026-07
 */
public class LlmExplorer implements Explorer {
    /** Section header introducing the structured findings in the LLM response. */
    private static final String FINDINGS_PREFIX = "FINDINGS:";

    /** Section header introducing the candidate approaches (pipe-separated). */
    private static final String APPROACHES_PREFIX = "APPROACHES:";

    private final Function<String, String> llm;

    private final ExploreBudget budget;

    /**
     * Constructs an LLM-backed explorer.
     *
     * @param llm function that takes a prompt and returns the LLM response text
     * @param budget exploration budget (max rounds, subagents, timeout)
     */
    public LlmExplorer(Function<String, String> llm, ExploreBudget budget) {
        if (llm == null) {
            throw new IllegalArgumentException("llm must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        this.llm = llm;
        this.budget = budget;
    }

    /**
     * Constructs with default budget.
     *
     * @param llm function that takes a prompt and returns the LLM response text
     */
    public LlmExplorer(Function<String, String> llm) {
        this(llm, ExploreBudget.DEFAULT);
    }

    @Override
    public ExplorationResult explore(String userInput, ExploreBudget budget) {
        if (userInput == null || userInput.isBlank()) {
            return new ExplorationResult("");
        }

        String prompt = buildExplorationPrompt(userInput);
        String response;
        try {
            response = llm.apply(prompt);
        } catch (IllegalStateException | NullPointerException e) {
            // Intentional catch-all (G.ERR.02): the injected Function<String,String>
            // interface cannot throw checked exceptions, so the production wiring
            // (EdpaAutoConfiguration.modelExploringFunction) rethrows unchecked
            // subtypes unchanged AND wraps checked Exception failures as an
            // IllegalStateException — itself a RuntimeException. Catching a narrower
            // type would silently swallow one of these two branches, so the broad
            // catch is load-bearing. Both map to an empty result (honest degradation,
            // asserted by EdpaAutoConfigurationTest.modelExploringFunction_modelThrowsReturnsEmpty).
            return new ExplorationResult("");
        }

        if (response == null || response.isBlank()) {
            return new ExplorationResult("");
        }

        return parseResponse(response);
    }

    /**
     * Builds the exploration prompt — asks the LLM to analyze the problem from
     * multiple angles and propose candidate approaches.
     *
     * @param userInput the user's original task
     * @return the exploration prompt
     */
    private static String buildExplorationPrompt(String userInput) {
        return """
                你是一个问题探索专家。请对以下任务进行主动探索分析：

                任务：%s

                要求：
                1. 从至少 2 个不同角度分析这个任务
                2. 识别任务中的关键未知信息和潜在风险
                3. 提出 2-3 个候选解决方向

                格式（严格遵循）：
                FINDINGS: <结构化发现摘要>
                APPROACHES: <方向1> | <方向2> | <方向3>
                """.formatted(userInput);
    }

    /**
     * Parses the LLM response into an ExplorationResult.
     *
     * <p>Expected format:
     * <pre>
     * FINDINGS: <findings text>
     * APPROACHES: <approach1> | <approach2> | <approach3>
     * </pre>
     *
     * <p>If the format is not matched, the entire response is treated as findings
     * with no candidate approaches (honest degradation — don't lose information).
     *
     * @param response the raw LLM response
     * @return parsed exploration result
     */
    private static ExplorationResult parseResponse(String response) {
        List<String> approaches = new ArrayList<>();
        StringBuilder findingsBuf = new StringBuilder();
        boolean inFindings = false;

        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith(FINDINGS_PREFIX)) {
                inFindings = true;
                appendIfNonEmpty(findingsBuf, trimmed.substring(FINDINGS_PREFIX.length()).trim());
            } else if (upper.startsWith(APPROACHES_PREFIX)) {
                inFindings = false;
                addApproachesIfNonEmpty(approaches, trimmed.substring(APPROACHES_PREFIX.length()).trim());
            } else if (inFindings) {
                findingsBuf.append("\n").append(trimmed);
            } else {
                continue; // non-section line outside FINDINGS block
            }
        }

        String findings = findingsBuf.toString().trim();
        if (findings.isEmpty()) {
            findings = response.trim();
        }

        return new ExplorationResult(findings, approaches);
    }

    /**
     * Appends the given text to the findings buffer only when non-empty, so blank
     * in-section lines do not introduce stray separators.
     *
     * @param buf   accumulating findings buffer
     * @param value text extracted after the FINDINGS prefix (already trimmed)
     */
    private static void appendIfNonEmpty(StringBuilder buf, String value) {
        if (!value.isEmpty()) {
            buf.append(value);
        }
    }

    /**
     * Splits the approaches payload on {@code |}, trims each entry, drops blanks,
     * and adds the survivors to {@code approaches} only when at least one remains.
     *
     * @param approaches accumulating candidate-approaches list
     * @param payload    text extracted after the APPROACHES prefix (already trimmed)
     */
    private static void addApproachesIfNonEmpty(List<String> approaches, String payload) {
        if (payload.isEmpty()) {
            return;
        }
        for (String part : payload.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                approaches.add(trimmed);
            }
        }
    }
}
