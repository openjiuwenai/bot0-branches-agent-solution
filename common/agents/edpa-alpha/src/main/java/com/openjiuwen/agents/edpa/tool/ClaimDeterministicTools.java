/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.tool;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 确定性理赔计算 Tool（Planner 默认 TOOL_CALL — 金额/阈值走工具不走 LLM）。
 *
 * <p>GEPA 确定性兜底铁律：涉数值/逻辑/合规的判定绝不 LLM-as-judge。
 * 从 pev ClaimTools（test fixture）提升为 edpa 生产 Tool——纯计算，零 LLM，
 * compute don't guess。
 *
 * <p><b>诚实边界（4-lens 校正）</b>：本类是示例确定性 Tool（保险理赔场景），
 * 需显式调用 {@code registerOnto(agent)} 注册。EdpaAutoConfiguration 默认不注册
 * （孤儿待接线）。金额/阈值是特定业务参数，非通用。
 *
 * <p>两个确定性算子：
 * <ul>
 *   <li><b>calcDeductible</b>：按共担比例计算正确赔付额（默认 85%）。部分责任案件按比例核减。</li>
 *   <li><b>authorizePayment</b>：判定大额上级复核（按险种阈值：医疗≥50000/重疾≥100000/意外≥30000）。</li>
 * </ul>
 *
 * @since 2026-07
 */
public final class ClaimDeterministicTools {
    /** 默认共担比例 85%（部分责任案件）。 */
    public static final BigDecimal DEFAULT_COINSURANCE_RATIO = new BigDecimal("0.85");

    /** 险种大额复核阈值（元）。 */
    public static final Map<String, BigDecimal> ESCALATION_THRESHOLDS = Map.of("医疗", new BigDecimal("50000"), "重疾",
            new BigDecimal("100000"), "意外", new BigDecimal("30000"));

    private ClaimDeterministicTools() {
    }

    /**
     * 注册 calcDeductible + authorizePayment 到 agent（双注册：abilityManager + resourceMgr）。
     *
     * @param agent target ReActAgent
     */
    public static void registerOnto(ReActAgent agent) {
        registerCalcDeductible(agent);
        registerAuthorizePayment(agent);
    }

    /**
     * calcDeductible：按共担比例计算正确赔付额（确定性算子）。
     *
     * @param claimAmount 申请金额（元）
     * @param coinsuranceRatio 共担比例（如 0.85）
     * @return 正确赔付额（claimAmount × ratio，保留 2 位小数）
     */
    static BigDecimal calcDeductible(BigDecimal claimAmount, BigDecimal coinsuranceRatio) {
        return claimAmount.multiply(coinsuranceRatio).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * authorizePayment：判定大额上级复核（按险种阈值）。
     *
     * @param amount 金额（元）
     * @param insuranceType 险种（医疗/重疾/意外）
     * @return true if amount ≥ threshold（需大额复核）
     */
    static boolean requiresEscalation(BigDecimal amount, String insuranceType) {
        BigDecimal threshold = ESCALATION_THRESHOLDS.getOrDefault(insuranceType, BigDecimal.ZERO);
        return amount.compareTo(threshold) >= 0;
    }

    private static void registerCalcDeductible(ReActAgent agent) {
        ToolCard card = ToolCard.builder().id("calcDeductible").name("calcDeductible")
                .description("按共担比例计算正确赔付额（确定性算子，零 LLM）。" + "部分责任案件按 85% 共担比例核减。参数：claim_amount（申请金额，元）、"
                        + "coinsurance_ratio（共担比例，默认 0.85）。")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("claim_amount", Map.of("type", "number", "description", "申请金额（元）"), "coinsurance_ratio",
                                Map.of("type", "number", "description", "共担比例（默认 0.85）")),
                        "required", List.of("claim_amount")))
                .build();
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                BigDecimal amount = new BigDecimal(String.valueOf(args.get("claim_amount")));
                BigDecimal ratio = args.containsKey("coinsurance_ratio")
                        ? new BigDecimal(String.valueOf(args.get("coinsurance_ratio")))
                        : DEFAULT_COINSURANCE_RATIO;
                BigDecimal approved = calcDeductible(amount, ratio);
                return Map.of("claim_amount", amount, "approved_amount", approved, "coinsurance_ratio", ratio, "note",
                        "确定性计算：" + amount + " × " + ratio + " = " + approved);
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    private static void registerAuthorizePayment(ReActAgent agent) {
        ToolCard card = ToolCard.builder().id("authorizePayment").name("authorizePayment")
                .description("判定大额上级复核（按险种阈值，确定性算子，零 LLM）。" + "医疗≥50000/重疾≥100000/意外≥30000。"
                        + "参数：amount（金额，元）、insurance_type（险种：医疗/重疾/意外）。")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("amount", Map.of("type", "number", "description", "金额（元）"), "insurance_type",
                                Map.of("type", "string", "description", "险种（医疗/重疾/意外）")),
                        "required", List.of("amount", "insurance_type")))
                .build();
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                BigDecimal amount = new BigDecimal(String.valueOf(args.get("amount")));
                String type = String.valueOf(args.getOrDefault("insurance_type", ""));
                boolean escalate = requiresEscalation(amount, type);
                BigDecimal threshold = ESCALATION_THRESHOLDS.getOrDefault(type, BigDecimal.ZERO);
                return Map.of("amount", amount, "insurance_type", type, "threshold", threshold, "hits_threshold",
                        escalate, "requires_escalation", escalate, "note", escalate ? "达到阈值，需大额复核" : "未达阈值，常规处理");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }
}
