/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereview.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * CompanyPolicyToolTest
 *
 * @since 2026-07-08
 */
class CompanyPolicyToolTest {
    @Test
    void shouldReturnPolicyRulesAndLimitsForExpenseItems() {
        Map<String, Object> result = CompanyPolicyTool.execute(Map.of(
                "items", List.of(
                        Map.of("name", "北京-上海机票", "amount", 4500, "category", "交通"),
                        Map.of("name", "酒店两晚", "amount", 1200, "category", "住宿"))));

        assertThat(result).containsKeys("policy_rules", "limits", "checked_at");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) result.get("policy_rules");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0)).containsEntry("category", "交通");
        assertThat(rules.get(1)).containsEntry("category", "住宿");

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) result.get("limits");
        assertThat(limits).containsEntry("flight", 5000).containsEntry("hotel_per_night", 600);
    }

    @Test
    void shouldReturnEmptyPolicyRulesWhenNoItemsProvided() {
        Map<String, Object> result = CompanyPolicyTool.execute(Map.of());

        assertThat(result).containsKeys("policy_rules", "limits", "checked_at");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) result.get("policy_rules");
        assertThat(rules).isEmpty();
    }

    @Test
    void shouldFallbackToOtherPolicyForUnknownCategory() {
        Map<String, Object> result = CompanyPolicyTool.execute(Map.of(
                "items", List.of(Map.of("name", "未知支出", "amount", 200, "category", "未知类别"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) result.get("policy_rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).containsEntry("limit", 1000);
    }
}
