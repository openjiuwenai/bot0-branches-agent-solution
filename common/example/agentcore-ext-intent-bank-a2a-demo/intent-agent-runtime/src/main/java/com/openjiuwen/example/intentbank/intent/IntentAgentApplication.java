/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.intent;

import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.core.retrieval.reranker.StandardReranker;
import com.openjiuwen.example.intentbank.common.BankDemoAgentFactory;
import com.openjiuwen.example.intentbank.common.BankDemoProperties;
import com.openjiuwen.example.intentbank.common.BankIntentFunctions;
import com.openjiuwen.example.intentbank.common.BankTools;
import com.openjiuwen.example.intentbank.common.IntentOnlyToolVisibilityRail;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** Entry runtime that routes every bank request through the intent suite. */
@SpringBootApplication
@EnableConfigurationProperties(BankDemoProperties.class)
public class IntentAgentApplication {
    private static final String WORKSPACE = "target/intent-agent-workspace";

    public static void main(String[] args) {
        SpringApplication.run(IntentAgentApplication.class, args);
    }

    @Bean
    AgentHandler intentAgentHandler(BankDemoProperties properties) {
        return new JiuwenCoreAgentExtHandler(BankDemoAgentFactory.create("intent-bank-router", "IntentBankRouter",
                "Routes bank and local utility requests", """
                        你是银行业务统一入口。每个用户请求都必须先交给 intent_match，不能自行选择业务工具。
                        intent_match 会在同一次调用中完成远端 Agent 或本地工具执行，直接依据其结果回答。
                        当结果 status 为 INTENT_CHANGED 且包含 latestSemantic 时，必须使用 latestSemantic 再次调用
                        intent_match，不能结束本轮会话。复杂请求先使用 DeepAgent 计划能力拆成单一业务步骤，
                        每个步骤分别调用 intent_match，完成全部步骤后再汇总结果。
                        """, WORKSPACE, true,
                List.of(BankTools.calculator(), BankTools.currentDate(), BankTools.weather()),
                List.of(new IntentOnlyToolVisibilityRail()), properties));
    }

    @Bean
    Reranker intentReranker(BankDemoProperties properties) {
        return new StandardReranker(properties.rerankerConfig(), properties.getReranker().getMaxRetries(), null, null);
    }

    @Bean("calculatorIntentResult")
    IntentResultFunction calculatorIntentResult() {
        return BankIntentFunctions.invokeWithQuery(BankTools.CALCULATOR);
    }

    @Bean("dateIntentResult")
    IntentResultFunction dateIntentResult() {
        return BankIntentFunctions.currentDate();
    }

    @Bean("weatherIntentResult")
    IntentResultFunction weatherIntentResult() {
        return BankIntentFunctions.invokeWithQuery(BankTools.WEATHER);
    }

    @Bean("bankFallbackIntentResult")
    IntentResultFunction fallbackIntentResult() {
        return BankIntentFunctions.fallback();
    }
}
