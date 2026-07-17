/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * ClaimDeterministicTools bearing tests — deterministic calculators (零 LLM, compute don't guess).
 *
 * @since 2026-07
 */
class ClaimDeterministicToolsTest {
    @Test
    void calcDeductibleDefault85Percent() {
        // 50000 × 0.85 = 42500 (pev ClaimTools 的标准 case)
        BigDecimal approved = ClaimDeterministicTools.calcDeductible(new BigDecimal("50000"),
                ClaimDeterministicTools.DEFAULT_COINSURANCE_RATIO);
        assertThat(approved).isEqualByComparingTo(new BigDecimal("42500.00"));
    }

    @Test
    void calcDeductibleCustomRatio() {
        BigDecimal approved = ClaimDeterministicTools.calcDeductible(new BigDecimal("100000"), new BigDecimal("0.70"));
        assertThat(approved).isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void authorizePaymentMedicalHitsThreshold() {
        // 医疗 ≥50000 → escalation
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("50000"), "医疗")).isTrue();
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("49999"), "医疗")).isFalse();
    }

    @Test
    void authorizePaymentCriticalIllnessThreshold() {
        // 重疾 ≥100000
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("100000"), "重疾")).isTrue();
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("99999"), "重疾")).isFalse();
    }

    @Test
    void authorizePaymentAccidentThreshold() {
        // 意外 ≥30000
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("30000"), "意外")).isTrue();
        assertThat(ClaimDeterministicTools.requiresEscalation(new BigDecimal("29999"), "意外")).isFalse();
    }

    @Test
    void determinismSameInputSameOutput() {
        // 确定性：同输入同输出（纯函数，零随机）
        BigDecimal a = ClaimDeterministicTools.calcDeductible(new BigDecimal("50000"), new BigDecimal("0.85"));
        BigDecimal b = ClaimDeterministicTools.calcDeductible(new BigDecimal("50000"), new BigDecimal("0.85"));
        assertThat(a).isEqualByComparingTo(b);
    }

    @Test
    void escalationThresholdsComplete() {
        // 3 险种阈值都在
        assertThat(ClaimDeterministicTools.ESCALATION_THRESHOLDS).containsKeys("医疗", "重疾", "意外");
        assertThat(ClaimDeterministicTools.ESCALATION_THRESHOLDS.get("医疗"))
                .isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(ClaimDeterministicTools.ESCALATION_THRESHOLDS.get("重疾"))
                .isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(ClaimDeterministicTools.ESCALATION_THRESHOLDS.get("意外"))
                .isEqualByComparingTo(new BigDecimal("30000"));
    }
}
