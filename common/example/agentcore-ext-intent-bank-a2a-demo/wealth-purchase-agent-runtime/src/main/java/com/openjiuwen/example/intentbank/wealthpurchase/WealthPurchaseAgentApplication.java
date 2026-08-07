/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.wealthpurchase;

import com.openjiuwen.example.intentbank.common.BankDemoAgentFactory;
import com.openjiuwen.example.intentbank.common.BankDemoProperties;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.ConfirmationRail;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.IntentChangeTerminationRail;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.IntentAwareAskUserRail;
import com.openjiuwen.example.intentbank.common.BankTools;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** DeepAgent runtime for resumable wealth product purchases. */
@SpringBootApplication
@EnableConfigurationProperties(BankDemoProperties.class)
public class WealthPurchaseAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(WealthPurchaseAgentApplication.class, args);
    }

    @Bean
    AgentHandler wealthPurchaseAgentHandler(BankDemoProperties properties) {
        return new JiuwenCoreAgentHandler(BankDemoAgentFactory.create("wealth-purchase-agent", "WealthPurchaseAgent",
                "Collects purchase details and buys wealth products after confirmation", """
                        你是理财购买专员。购买必须同时获得产品名称和大于零的金额。缺少任一信息时必须调用
                        ask_user，参数 query 填写需要用户回答的问题，禁止直接用文本追问。信息齐全后调用 purchase_wealth。purchase_wealth 会在执行前
                        要求用户确认，收到确认后完成购买。若工具结果 status 为 INTENT_CHANGED，立即原样返回，
                        不继续购买，也不要处理新的业务目标。
                        """, "target/wealth-purchase-agent-workspace", false,
                List.of(BankTools.askUser(), BankTools.wealthPurchase()), List.of(new IntentAwareAskUserRail(),
                        new ConfirmationRail(BankTools.WEALTH_PURCHASE, "请确认是否购买这款理财产品。"),
                        new IntentChangeTerminationRail()),
                properties));
    }
}
