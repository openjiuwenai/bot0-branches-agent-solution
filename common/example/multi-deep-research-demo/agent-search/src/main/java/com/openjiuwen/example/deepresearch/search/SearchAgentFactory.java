/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

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
        agent.getAbilityManager().add(new LocalFunction(toolCard, toolFn));
        return agent;
    }
}
