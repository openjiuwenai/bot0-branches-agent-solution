/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Tests deterministic bank demo Tools and schemas. */
class BankToolsTest {
    @Test
    void returnsDeterministicBankResults() throws Exception {
        assertThat(result(BankTools.balance().invoke(Map.of("account", "工资卡"))))
                .containsEntry("handledBy", "balance-agent").containsEntry("account", "工资卡")
                .containsEntry("balance", 12800.50D);

        Map<String, Object> transfer = result(BankTools.transfer().invoke(Map.of("recipient", "张三", "amount", 100)));
        assertThat(transfer).containsEntry("handledBy", "transfer-agent").containsEntry("recipient", "张三");
        if (!(transfer.get("amount") instanceof BigDecimal transferAmount)) {
            throw new AssertionError("expected transfer amount");
        }
        assertThat(transferAmount).isEqualByComparingTo("100");

        Map<String, Object> purchase = result(
                BankTools.wealthPurchase().invoke(Map.of("product", "稳盈90天", "amount", 10000)));
        assertThat(purchase).containsEntry("handledBy", "wealth-purchase-agent").containsEntry("product", "稳盈90天");
        if (!(purchase.get("amount") instanceof BigDecimal purchaseAmount)) {
            throw new AssertionError("expected purchase amount");
        }
        assertThat(purchaseAmount).isEqualByComparingTo("10000");
    }

    @Test
    void executesLocalUtilityTools() throws Exception {
        assertThat(result(BankTools.calculator().invoke(Map.of("query", "6 * 7")))).containsEntry("status", "COMPLETED")
                .containsEntry("result", "42");
        assertThat(result(BankTools.calculator().invoke(Map.of("query", "1 / 0")))).containsEntry("status",
                "INVALID_INPUT");
        assertThat(result(BankTools.weather().invoke(Map.of("query", "上海天气")))).containsEntry("city", "上海")
                .containsEntry("weather", "多云，28C");
        assertThat(result(BankTools.currentDate().invoke(Map.of()))).containsKeys("date", "dayOfWeek");
    }

    @Test
    void publishesRequiredToolSchemas() {
        assertThat(required(BankTools.askUser())).containsExactly("query");
        assertThat(required(BankTools.transfer())).containsExactlyInAnyOrder("recipient", "amount");
        assertThat(required(BankTools.wealthPurchase())).containsExactlyInAnyOrder("product", "amount");
        assertThat(BankTools.calculator().getCard().getName()).isEqualTo(BankTools.CALCULATOR);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(com.openjiuwen.core.foundation.tool.Tool tool) {
        return (List<String>) tool.getCard().getInputParams().get("required");
    }
}
