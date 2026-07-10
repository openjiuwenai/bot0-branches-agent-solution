/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PevComponents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-backed Planner — asks the LLM to produce a JSON plan for the task + available tools,
 * then parses it into {@link PevComponents.Plan}.
 *
 * <p><b>Robustness contract (铁律① 诚实边界 / minimal+robust):</b> LLM output is not trusted.
 * Any parse failure (malformed JSON, no nodes, missing id/description) falls back to a single
 * {@code LLM_CALL} node whose description is the raw task. The plan stage never throws on
 * bad LLM output — it degrades to a trivially-correct plan instead. {@code type} defaults to
 * {@code LLM_CALL}; unknown types are coerced to {@code LLM_CALL}.
 *
 * <p>This is test-scope shared infra for the real-LLM e2e. Mirrors the honesty split:
 * mock tests carry hard control-flow断言; real-LLM here is soft-observe.
 */
final class LlmPlanner implements PevComponents.Planner {
    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final LlmClient llm;
    private final Map<String, String> tools; // name -> description (for prompt context)

    /**
     * Planner with no tools (pure LLM reasoning).
     *
     * @param llm LLM client used for planning
     */
    LlmPlanner(LlmClient llm) {
        this(llm, Map.of());
    }

    /**
     * Planner aware of named tools — tool names are surfaced in the planning prompt.
     *
     * @param llm LLM client used for planning
     * @param tools tool name to description map
     */
    LlmPlanner(LlmClient llm, Map<String, String> tools) {
        this.llm = llm;
        this.tools = tools == null ? Map.of() : tools;
    }

    @Override
    public PevComponents.Plan plan(String userInput) {
        String raw = llm.chat(buildPrompt(userInput));
        List<PevComponents.PlanNode> nodes = parseNodes(raw);
        if (nodes.isEmpty()) {
            // Robustness: degrade to a single LLM_CALL node = the task itself.
            nodes = List.of(new PevComponents.PlanNode("node-1", userInput));
        }
        return new PevComponents.Plan(userInput, nodes);
    }

    private String buildPrompt(String task) {
        StringBuilder toolBlock = new StringBuilder();
        if (tools.isEmpty()) {
            toolBlock.append("（无可用工具）");
        } else {
            toolBlock.append("可用工具：").append(LINE_SEPARATOR);
            tools.forEach((name, desc) -> toolBlock.append("- ").append(name).append(": ").append(desc)
                    .append(LINE_SEPARATOR));
        }
        return """
                你是一个任务规划器。请把下面的任务拆成 1-5 个执行步骤，每步调用一个工具或做一段 LLM 推理。
                只回复 JSON，不要任何解释或 markdown：
                {"nodes":[{"id":"node-1","description":"步骤描述","type":"TOOL_CALL 或 LLM_CALL"}]}

                任务：%s
                %s

                要求：
                - id 形如 node-1, node-2 ...
                - type 只能是 TOOL_CALL（调用某工具）或 LLM_CALL（纯推理）
                - TOOL_CALL 的 description 里写明要用的工具名
                """.formatted(task, toolBlock);
    }

    private static final Pattern NODE_OBJECT = Pattern.compile("\\{[^{}]*\"id\"[^{}]*\\}", Pattern.DOTALL);

    private static List<PevComponents.PlanNode> parseNodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<PevComponents.PlanNode> out = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        Matcher m = NODE_OBJECT.matcher(raw);
        int idx = 1;
        while (m.find()) {
            String obj = m.group();
            String id = extractString(obj, "id");
            String desc = extractString(obj, "description");
            String type = extractString(obj, "type");
            if (desc == null || desc.isBlank()) {
                continue; // skip nodes without a usable description
            }
            if (id == null || id.isBlank() || !usedIds.add(id)) {
                id = "node-" + idx;
                while (!usedIds.add(id)) {
                    idx++;
                    id = "node-" + idx;
                }
            }
            idx++;
            // type unused beyond validation for now; PlanNode carries only id+description.
            // type default LLM_CALL is honored by ToolBackedExecutor (no tool name => LLM).
            out.add(new PevComponents.PlanNode(id, desc));
        }
        return out;
    }

    /**
     * Extract a JSON string value for the given key from a flat object fragment.
     *
     * @param json JSON object fragment
     * @param key target key
     * @return extracted string value, or empty string when absent
     */
    private static String extractString(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                char n = json.charAt(++j);
                sb.append(n == 'n' ? (char) 10 : n);
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
