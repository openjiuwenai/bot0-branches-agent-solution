/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.resourcemanager.ResourceMgr;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Virtual tool the LLM calls to express replan intent ("abandon current path, try new strategy").
 *
 * <p><b>Registration contract</b>: {@link com.openjiuwen.core.singleagent.AbilityManager#add(Object)}
 * only accepts {@link ToolCard} / WorkflowCard / AgentCard / McpServerConfig — passing a raw
 * {@code Tool} logs {@code "Unknown ability type"} and silently drops it (LLM never sees
 * {@code __replan__}). Worse, even with the card registered, tool execution is resolved via
 * {@code Runner.resourceMgr().getTool(...)} (see {@code AbilityManager.executeSingleToolCall} →
 * {@code getToolFromResourceMgr}), NOT from the AbilityManager's card map. So a working
 * registration needs BOTH:
 * <ol>
 *   <li>{@code agent.getAbilityManager().add(card)} — so {@code listToolInfo()} surfaces
 *       {@code __replan__} to the LLM (visibility).</li>
 *   <li>{@code Runner.resourceMgr().addTool(tool, null)} — so {@code executeSingleToolCall}
 *       can resolve and invoke the executable (dispatch).</li>
 * </ol>
 * <b>id==name invariant</b>: {@code executeSingleToolCall} resolves the executable key as
 * {@code card.getId()} (fallback {@code card.getName()}); {@code ResourceMgr.addTool} stores it
 * under {@code card.getId()} (auto-generated UUID when null). The two keys match ONLY when
 * {@code id} is set explicitly — hence the card sets {@code .id(__replan__)} == {@code .name(...)}.
 * Use {@link #registerOnto(ReActAgent)} to do both atomically.
 
  * @since 2026-07*/
public class ReplanTool extends Tool {

    public static final String TOOL_NAME = "__replan__";
    public static final String ARG_REPLAN_REASON = "replan_reason";
    public static final String ARG_NEW_APPROACH = "new_approach";

    private static final ToolCard CARD = ToolCard.builder()
            .id(TOOL_NAME)
            .name(TOOL_NAME)
            .description("调用此工具表达需要调整当前策略的意图。"
                    + "参数：replan_reason（为什么要 replan）、new_approach（新策略是什么）。")
            .inputParams(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            ARG_REPLAN_REASON, Map.of("type", "string", "description", "为什么要 replan"),
                            ARG_NEW_APPROACH, Map.of("type", "string", "description", "新策略是什么")),
                    "required", List.of(ARG_REPLAN_REASON)))
            .build();

    /** 构造 ReplanTool，注册固定的 __replan__ 工具卡片。 */
    public ReplanTool() {
        super(CARD);
    }

    /** 返回工具卡片（名称/描述），供 ability manager 注册与 LLM 可见。 */
    @Override
    public ToolCard getCard() {
        return CARD;
    }

    /** 同步调用：返回结构化确认 Map，含"请总结教训"提示引导 LLM 主动做教训提炼。 */
    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
        return Map.of("status", "replan_recorded",
                "message", "Replan 已确认。请总结之前的尝试经验（哪些可用、哪些需改变）"
                + "，然后基于新方向继续。");
    }

    /** 流式调用：将同步结果包装成单元素迭代器返回。 */
    @Override
    public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
        return List.<Object>of(invoke(args, kwargs)).iterator();
    }

    /**
     * Correctly register this tool onto a {@link ReActAgent}: card into AbilityManager
     * (LLM visibility) + executable into {@link Runner#resourceMgr()} (dispatch).
     *
     * <p>This is the only supported registration path — see class javadoc.
     *
     * @param agent the target ReActAgent
     * @return the registered ReplanTool instance (for fluent assertions in tests)
     */
    public static ReplanTool registerOnto(ReActAgent agent) {
        ReplanTool tool = new ReplanTool();
        // 1. LLM visibility: listToolInfo() walks AbilityManager.tools (ToolCard values)
        agent.getAbilityManager().add(tool.getCard());
        // 2. Dispatch: executeSingleToolCall resolves the executable via Runner.resourceMgr()
        ResourceMgr resourceMgr = Runner.resourceMgr();
        resourceMgr.addTool(tool, null);
        return tool;
    }
}