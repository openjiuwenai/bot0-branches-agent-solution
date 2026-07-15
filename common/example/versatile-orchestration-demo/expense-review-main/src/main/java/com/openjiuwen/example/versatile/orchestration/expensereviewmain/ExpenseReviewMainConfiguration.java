/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereviewmain;

import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Assembles the expense-review-main ReActAgent and wraps it in the agentcore-ext
 * handler. The remote expense-review workflow agent is wired as an A2A tool via
 * {@code openjiuwen.service.a2a.remote-agents} (RemoteA2aToolInstaller), exposing
 * the workflow's review_expense skill as a callable tool.
 *
 * @since 2026-07-08
 */
@Configuration(proxyBeanMethods = false)
public class ExpenseReviewMainConfiguration {
    static final String AGENT_ID = "expense-review-main";

    private static final String SYSTEM_PROMPT = """
            你是一个费用报销审核主控助手。
            你可以使用 review_expense 工具来审核报销申请。
            当用户提交报销内容时，调用 review_expense 工具。
            如果工具返回需要审批，引导用户进行审批操作。
            收到工具返回的结果后，用中文向用户总结审核结果。""";

    @Bean
    AgentHandler expenseReviewMainHandler(
            @Value("${expense-review-main.model-provider:openai}") String modelProvider,
            @Value("${expense-review-main.api-key:}") String apiKey,
            @Value("${expense-review-main.api-base:http://localhost:4000/v1}") String apiBase,
            @Value("${expense-review-main.model-name:gpt-4o-mini}") String modelName,
            @Value("${expense-review-main.ssl-verify:true}") boolean sslVerify) {
        return new JiuwenCoreAgentExtHandler(
                buildReActAgent(modelProvider, apiKey, apiBase, modelName, sslVerify));
    }

    static ReActAgent buildReActAgent(String modelProvider, String apiKey, String apiBase,
                                      String modelName, boolean sslVerify) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("报销审核主控 ReActAgent — 通过 LLM 决策调用 Expense Review Workflow Agent")
                .build();
        ReActAgent agent = new ReActAgent(card);

        // Only the remote expense-review A2A delegate tool is exposed (wired via
        // openjiuwen.service.a2a.remote-agents). No other tools.
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                .maxIterations(5)
                .build()
                .configureModelClient(modelProvider, apiKey, apiBase, modelName, sslVerify);
        ModelRequestConfig modelConfig = config.getModelConfigObj();
        modelConfig.setTemperature(0.0);
        modelConfig.setMaxTokens(256);
        agent.configure(config);

        return agent;
    }
}
