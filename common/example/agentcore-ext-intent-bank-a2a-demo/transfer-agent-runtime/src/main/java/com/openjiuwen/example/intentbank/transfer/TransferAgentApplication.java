/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.transfer;

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

/** DeepAgent runtime for resumable bank transfers. */
@SpringBootApplication
@EnableConfigurationProperties(BankDemoProperties.class)
public class TransferAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferAgentApplication.class, args);
    }

    @Bean
    AgentHandler transferAgentHandler(BankDemoProperties properties) {
        return new JiuwenCoreAgentHandler(BankDemoAgentFactory.create("transfer-agent", "TransferAgent",
                "Collects transfer details and executes confirmed transfers", """
                        你是银行转账专员。转账必须同时获得收款人和大于零的金额。缺少任一信息时必须调用
                        ask_user，参数 query 填写需要用户回答的问题，禁止直接用文本追问。信息齐全后调用 execute_transfer。execute_transfer 会在执行前
                        要求用户确认，收到确认后完成转账。若工具结果 status 为 INTENT_CHANGED，立即原样返回，
                        不继续转账，也不要处理新的业务目标。
                        """, "target/transfer-agent-workspace", false,
                List.of(BankTools.askUser(), BankTools.transfer()),
                List.of(new IntentAwareAskUserRail(), new ConfirmationRail(BankTools.TRANSFER, "请确认是否执行这笔转账。"),
                        new IntentChangeTerminationRail()),
                properties));
    }
}
