/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.balance;

import com.openjiuwen.example.intentbank.common.BankAgentDefinition;
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

/**
 * DeepAgent runtime for balance queries.
 *
 * @since 0.1.0
 */
@SpringBootApplication
@EnableConfigurationProperties(BankDemoProperties.class)
public class BalanceAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(BalanceAgentApplication.class, args);
    }

    @Bean
    AgentHandler balanceAgentHandler(BankDemoProperties properties) {
        BankAgentDefinition definition = new BankAgentDefinition("balance-agent", "BalanceAgent",
                "Handles account balance queries", "你是余额查询专员。收到请求后调用 query_balance，并用简洁中文返回余额、币种和账户。",
                "target/balance-agent-workspace", false);
        return new JiuwenCoreAgentHandler(
                BankDemoAgentFactory.create(definition, List.of(BankTools.balance()), List.of(), properties));
    }
}
