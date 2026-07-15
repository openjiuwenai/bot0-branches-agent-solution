/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.rails.interrupt.AskUserRail;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Library-tier factory: turn {@link SearchAgentProperties} into a configured
 * {@link ReActAgent} with the {@code web_search} tool registered on its
 * {@code AbilityManager}.
 *
 * <p>The {@code use-stub} flag in {@link SearchAgentProperties} decides which
 * static method backs the tool: {@link WebSearchTool#search} for prod (Tavily)
 * or {@link StubWebSearchTool#search} for stub (fixture).
 *
 * @since 2026-07-06
 */
public final class SearchAgentFactory {
    private static final String TOOL_NAME = "web_search";
    private static final String TOOL_DESCRIPTION = """
            Search the public web, classify each hit as official / blog / news / forum,
            reweight vendor-official hits, and return a ranked
            {results:[{url,title,snippet,source_kind,score}]} JSON payload.""";

    private static final String ASK_USER_TOOL_ID = "search_agent_ask_user";
    private static final String ASK_USER_TOOL_NAME = "ask_user";
    private static final String ASK_USER_TOOL_DESCRIPTION = """
            Ask the caller a single clarifying question when the incoming query is ambiguous
            (e.g., "DeepSeek 官网报价" — which model? V2? R1? coder?). MUST be called BEFORE
            web_search whenever the vendor/model/version is not uniquely determined. Emits
            an interrupt via AskUserRail; the caller's next-turn text is delivered back as
            the tool result.""";

    private SearchAgentFactory() {
    }

    /**
     * Builds a {@link ReActAgent} preloaded with the {@code web_search} tool.
     *
     * @param props the search-agent configuration
     * @return the configured agent
     */
    public static ReActAgent build(SearchAgentProperties props) {
        props.requireConfigured();

        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .sysOperationId(props.getSysOperationId())
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt())))
                .build()
                .configureModelClient(
                        props.getProvider(),
                        props.getApiKey(),
                        props.getApiBase(),
                        props.getModelName(),
                        props.isSslVerify());
        if (config.getModelConfigObj() != null) {
            config.getModelConfigObj().setTemperature(props.getTemperature());
            config.getModelConfigObj().setTopP(props.getTopP());
        }
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name(props.getAgentName())
                .description(props.getAgentDescription())
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        ToolCard toolCard = ToolCard.builder()
                .id(TOOL_NAME)
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputParams(props.webSearchInputSchema())
                .build();
        Function<Map<String, Object>, Object> toolFn = props.isUseStub()
                ? StubWebSearchTool::search
                : WebSearchTool::search;
        Tool webSearchTool = new LocalFunction(toolCard, toolFn);
        Runner.resourceMgr().addTool(webSearchTool, agent.getCard().getId());
        agent.getAbilityManager().add(toolCard);

        mountAskUserTool(agent);
        agent.registerRail(new AskUserRail());
        return agent;
    }

    /**
     * Registers an {@code ask_user} tool that AskUserRail intercepts.
     *
     * <p>The rail throws an {@code InterruptRequest} on the first invocation (no user input yet),
     * which surfaces at the runtime as {@code __interaction__} / {@code TYPE_INTERRUPT}. When the
     * caller supplies a next-turn text, the rail resumes with that text as the tool result — the
     * tool body itself is never actually executed, so this echo function is only a placeholder.
     *
     * @param agent the ReAct agent that the {@code ask_user} tool is bound to
     */
    private static void mountAskUserTool(ReActAgent agent) {
        ToolCard askUserCard = ToolCard.builder()
                .id(ASK_USER_TOOL_ID)
                .name(ASK_USER_TOOL_NAME)
                .description(ASK_USER_TOOL_DESCRIPTION)
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of(
                                        "type", "string",
                                        "description", "The clarifying question to ask the caller.")),
                        "required", List.of("question")))
                .build();
        Tool askUserTool = new LocalFunction(askUserCard, inputs -> {
            Object question = inputs != null ? inputs.get("question") : null;
            return question != null ? String.valueOf(question) : "";
        });
        Runner.resourceMgr().addTool(askUserTool, agent.getCard().getId());
        agent.getAbilityManager().add(askUserCard);
    }
}
