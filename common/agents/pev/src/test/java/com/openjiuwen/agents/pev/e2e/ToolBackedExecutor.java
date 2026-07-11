/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool-backed Executor — routes each {@link PevComponents.PlanNode} to either a registered
 * tool or an LLM call.
 *
 * <p><b>Routing rule (minimal, robust):</b> a node is treated as a tool call iff its
 * description contains a registered tool name (longest-match wins to disambiguate prefixes).
 * The tool receives args parsed from the description (best-effort: {@code caseNo: ...} /
 * {@code "caseNo": "..."} patterns, else empty map). Otherwise the node runs through
 * {@link LlmClient#chat(String)} as a pure reasoning step.
 *
 * <p><b>Failure mapping (honesty edge):</b> a tool throwing maps to
 * {@link NodeResult.DeviceFailure}({@code isTimeout=false}) — matching the kernel's
 * 3-state sealed taxonomy so {@link com.openjiuwen.agents.pev.kernel.PevKernel} can dispatch
 * {@code AcceptPartial} (never retry a broken device). LLM call failures propagate as
 * runtime exceptions (the e2e is soft-observe; an infrastructure outage aborting the run
 * is the correct signal, not a silent degraded result).
 */
final class ToolBackedExecutor implements PevComponents.Executor {
    private final LlmClient llm;
    private final Map<String, java.util.function.Function<Map<String, Object>, String>> tools;

    ToolBackedExecutor(LlmClient llm, Map<String, java.util.function.Function<Map<String, Object>, String>> tools) {
        this.llm = llm;
        this.tools = tools == null ? Map.of() : tools;
    }

    @Override
    public Map<String, NodeResult> execute(List<PevComponents.PlanNode> nodes) {
        Map<String, NodeResult> results = new LinkedHashMap<>();
        for (PevComponents.PlanNode node : nodes) {
            results.put(node.id(), executeNode(node));
        }
        return results;
    }

    private NodeResult executeNode(PevComponents.PlanNode node) {
        String toolName = matchTool(node.description());
        if (toolName != null) {
            java.util.function.Function<Map<String, Object>, String> fn = tools.get(toolName);
            Map<String, Object> args = parseArgs(node.description());
            try {
                String result = fn.apply(args);
                return new NodeResult.Success(result);
            } catch (IllegalStateException e) {
                // Device failure — kernel will dispatch AcceptPartial, never retry.
                return new NodeResult.DeviceFailure(node.id(), e.getMessage(), false);
            }
        }
        // LLM_CALL node: feed the description to the LLM.
        String ans = llm.chat(node.description());
        return new NodeResult.Success(ans);
    }

    /**
     * Longest-name match so e.g. {@code getCaseStatus} wins over prefixes.
     *
     * @param description plan node description
     * @return matched tool name, or null when no registered tool is mentioned
     */
    private String matchTool(String description) {
        String hit = null;
        for (String name : tools.keySet()) {
            if (description.contains(name) && (hit == null || name.length() > hit.length())) {
                hit = name;
            }
        }
        return hit;
    }

    /**
     * Parse {@code key: value} / {@code "key":"value"} pairs from the description.
     *
     * @param description plan node description
     * @return parsed argument map
     */
    private static Map<String, Object> parseArgs(String description) {
        Map<String, Object> args = new LinkedHashMap<>();
        // match: caseNo: CLM-2026-REDUCE  |  "caseNo": "CLM-2026-REDUCE"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*:\\s*\"?([A-Za-z0-9_\\-]+)\"?").matcher(description);
        while (m.find()) {
            args.put(m.group(1), m.group(2));
        }
        // Robustness (铁律①: LLM output not trusted): the planner LLM rarely emits a clean
        // `caseNo: ...` pair — it writes free-text like "调用 getClaimInfo 查询案件
        // CLM-2026-REDUCE". Without a caseNo, the tool fixture throws → spurious DeviceFailure
        // that masquerades as a broken device. Recover by scanning the description for a known
        // case token (CLM-YYYY-XXX) and injecting it as caseNo when absent. This keeps the
        // tool channel honest: only a genuinely thrown tool (DeviceFailure* fixture) maps to
        // DeviceFailure, not a parser gap.
        if (!args.containsKey("caseNo")) {
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("CLM-\\d{4}-[A-Z]+").matcher(description);
            if (cm.find()) {
                args.put("caseNo", cm.group());
            }
        }
        return args;
    }
}
