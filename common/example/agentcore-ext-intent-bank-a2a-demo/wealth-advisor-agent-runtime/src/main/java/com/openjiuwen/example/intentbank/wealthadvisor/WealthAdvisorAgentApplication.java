/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.wealthadvisor;

import com.openjiuwen.example.intentbank.common.BankDemoAgentFactory;
import com.openjiuwen.example.intentbank.common.BankDemoProperties;
import com.openjiuwen.example.intentbank.common.BankTools;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** DeepAgent runtime for wealth product recommendations. */
@SpringBootApplication
@EnableConfigurationProperties(BankDemoProperties.class)
public class WealthAdvisorAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(WealthAdvisorAgentApplication.class, args);
    }

    @Bean
    AgentHandler wealthAdvisorAgentHandler(BankDemoProperties properties) {
        return new JiuwenCoreAgentHandler(BankDemoAgentFactory.create("wealth-advisor-agent", "WealthAdvisorAgent",
                "Recommends wealth products", "你是理财推荐专员。调用 recommend_wealth 分析完整请求，并返回产品、风险等级和参考收益率。",
                "target/wealth-advisor-agent-workspace", false, List.of(BankTools.wealthRecommendation()), List.of(),
                properties));
    }
}
