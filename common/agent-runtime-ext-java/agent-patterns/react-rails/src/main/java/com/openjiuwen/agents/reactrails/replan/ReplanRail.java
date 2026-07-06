/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beta cognitive rail for ReActAgent — replan counting + over-limit escalation.
 *
 * <p>Hooks {@code afterModelCall}: when the LLM calls {@code __replan__} (via
 * {@link ReplanTool}), increments the replan counter. If count exceeds maxReplan,
 * escalates to {@code forceFinish(degraded)} — honest terminal, stops the loop.
 *
 * <p><b>IFF 契约</b>: replan count ⟺ over-limit escalate. Strip the count increment
 * → canReplan永远 true →永不 forceFinish → 测试 RED.
 */
public class ReplanRail extends AgentRail {

    public static final String REPLAN_EXCEEDED_KEY = "replan_exceeded";
    public static final String DEGRADED_KEY = "degraded";
    public static final String REPLAN_COUNT_KEY = "replan_count";
    public static final String MAX_REPLAN_KEY = "max_replan";

    private final int maxReplan;
    private int replanCount = 0;

    /** 指定最大 replan 次数构造 rail。 */
    public ReplanRail(int maxReplan) {
        this.maxReplan = maxReplan;
    }

    /** 默认构造，最大 replan 次数取 2。 */
    public ReplanRail() {
        this(2);
    }

    /** Current replan count (test observation). */
    public synchronized int replanCount() {
        return replanCount;
    }

    /** 模型回调钩子：检测 __replan__ 调用并计数，超限则 forceFinish 降级终态。 */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }
        if (msg.getToolCalls() == null) {
            return;
        }

        for (ToolCall tc : msg.getToolCalls()) {
            if (ReplanTool.TOOL_NAME.equals(tc.getName())) {
                replanCount++;
                if (replanCount > maxReplan) {
                    ctx.requestForceFinish(degradedResult());
                }
                // Else: allow the replan — agent executes ReplanTool → gets confirmation → tries new strategy
            }
        }
    }

    private Map<String, Object> degradedResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(REPLAN_EXCEEDED_KEY, true);
        result.put(DEGRADED_KEY, true);
        result.put(REPLAN_COUNT_KEY, replanCount);
        result.put(MAX_REPLAN_KEY, maxReplan);
        result.put("output", "Replan 次数已达上限 " + maxReplan + "，降级终态");
        return result;
    }
}