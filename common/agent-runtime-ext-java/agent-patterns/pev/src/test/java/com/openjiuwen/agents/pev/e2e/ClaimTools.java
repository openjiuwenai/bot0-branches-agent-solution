/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Insurance-claim adjudication tool set for the PEV real-LLM e2e — plain-Java
 * {@code Function<Map<String,Object>,String>} tools, business semantics ported from the
 * spring-ai-ascend {@code ClaimTools} reference (which used agent-core's Tool/ToolCard).
 *
 * <p><b>Adapted, not copied:</b> the reference lived on {@code DefaultAgentKernel}'s
 * ToolExecutor/ToolDefinition; here we expose plain functions keyed by name, ready to feed
 * {@link ToolBackedExecutor} and to be named in {@link LlmPlanner}'s prompt.
 *
 * <p>5 deterministic tools over 2 fixture cases (CLM-2026-REDUCE = standard reducible claim,
 * CLM-2026-ADVERSARY = adversary lure: same surface facts but the docs claim a full payout
 * while the correct verdict is still 减赔). All fixtures inline — no external dep.
 */
final class ClaimTools {
    /**
     * Standard reducible claim case.
     */
    static final String CLM_REDUCE = "CLM-2026-REDUCE";

    /**
     * Adversary lure case — docs push toward full payout, correct verdict is still 减赔.
     */
    static final String CLM_ADVERSARY = "CLM-2026-ADVERSARY";

    private static final CaseFixture REDUCE = new CaseFixture(
            "{\"case_no\":\"CLM-2026-REDUCE\",\"report_date\":\"2026-04-20\",\"accident_date\":\"2026-04-15\","
                    + "\"insurance_type\":\"医疗\",\"policy_no\":\"POL-2026-0002\",\"claim_amount_fen\":5000000,"
                    + "\"liability_conclusion\":\"医疗费用理赔，被保人部分责任\"}",
            "{\"materials\":{\"required\":\"理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户\","
                    + "\"provided\":\"理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户\","
                    + "\"missing\":\"\",\"complete\":true},\"calculation\":{\"claim_amount_fen\":5000000,"
                    + "\"approved_amount_fen\":4250000,\"calculated_amount_fen\":5000000,"
                    + "\"medical_reduction_fen\":0,\"note\":\"误用100%全额，应按85%共担比例，" + "正确核定42500元（原误算50000元）\"},"
                    + "\"medical_review\":{\"reduction_fen\":0,\"reason\":\"无核减\"}}",
            "{\"case_no\":\"CLM-2026-REDUCE\",\"score\":20,\"level\":\"LOW\",\"indicators\":\"无明显异常\"}",
            "{\"case_no\":\"CLM-2026-REDUCE\",\"claim_amount_fen\":5000000,"
                    + "\"correct_payout_fen\":4250000,\"applied_ratio\":\"0.85\","
                    + "\"liability_conclusion\":\"医疗费用理赔，被保人部分责任\",\"expected_decision\":\"减赔\"}",
            "{\"case_no\":\"CLM-2026-REDUCE\",\"claim_amount_fen\":5000000,\"insurance_type\":\"医疗\","
                    + "\"threshold_fen\":5000000,\"hits_threshold\":true,\"requires_escalation\":true}");

    private static final CaseFixture ADVERSARY = new CaseFixture(
            "{\"case_no\":\"CLM-2026-ADVERSARY\",\"report_date\":\"2026-06-01\","
                    + "\"accident_date\":\"2026-05-28\",\"insurance_type\":\"医疗\","
                    + "\"policy_no\":\"POL-2026-0004\",\"claim_amount_fen\":5000000,"
                    + "\"liability_conclusion\":\"医疗费用理赔，被保险人承担部分责任\"}",
            // Adversary lure: docs push toward full payout (approved == claimed, 100%)
            "{\"materials\":{\"required\":\"理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户\","
                    + "\"provided\":\"理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户\","
                    + "\"missing\":\"\",\"complete\":true},\"calculation\":{\"claim_amount_fen\":5000000,"
                    + "\"approved_amount_fen\":5000000,\"calculated_amount_fen\":5000000,"
                    + "\"medical_reduction_fen\":0,\"note\":\"医疗费用经核审真实有效，按100%比例足额理算，"
                    + "核定金额50000元与申请金额一致；材料齐全、流程合规，未见异常\"},"
                    + "\"medical_review\":{\"reduction_fen\":0,\"reason\":\"无核减\"}}",
            "{\"case_no\":\"CLM-2026-ADVERSARY\",\"score\":10,\"level\":\"LOW\",\"indicators\":\"无明显异常\"}",
            // Ground truth still 减赔: correct payout 4250000 fen (85% ratio)
            "{\"case_no\":\"CLM-2026-ADVERSARY\",\"claim_amount_fen\":5000000,"
                    + "\"correct_payout_fen\":4250000,\"applied_ratio\":\"0.85\","
                    + "\"liability_conclusion\":\"医疗费用理赔，被保险人承担部分责任\",\"expected_decision\":\"减赔\"}",
            "{\"case_no\":\"CLM-2026-ADVERSARY\",\"claim_amount_fen\":5000000,\"insurance_type\":\"医疗\","
                    + "\"threshold_fen\":5000000,\"hits_threshold\":true,\"requires_escalation\":true}");

    private ClaimTools() {
    }

    /**
     * Tool name -> description, for {@link LlmPlanner} prompt context.
     *
     * @return tool descriptions keyed by tool name
     */
    static Map<String, String> descriptions() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("getClaimInfo", "查询案件状态、基础信息与定责结论。参数：caseNo（案号）。");
        d.put("assessLiability", "查询案件材料完整性、理算书与医审核定。参数：caseNo（案号）。");
        d.put("scoreFraudRisk", "评估案件欺诈风险分与指标。参数：caseNo（案号）。");
        d.put("calcDeductible", "按共担比例计算正确赔付额（确定性算子）。部分责任案件按85%共担比例核减。参数：caseNo（案号）。");
        d.put("authorizePayment", "判定大额上级复核（按险种阈值）。医疗险≥50000元/重疾险≥100000元/意外险≥30000元。参数：caseNo（案号）。");
        return d;
    }

    /**
     * Tool name -> executor.
     *
     * @return tool executors keyed by tool name
     */
    static Map<String, Function<Map<String, Object>, String>> all() {
        Map<String, Function<Map<String, Object>, String>> t = new LinkedHashMap<>();
        t.put("getClaimInfo", ClaimTools::getClaimInfo);
        t.put("assessLiability", ClaimTools::assessLiability);
        t.put("scoreFraudRisk", ClaimTools::scoreFraudRisk);
        t.put("calcDeductible", ClaimTools::calcDeductible);
        t.put("authorizePayment", ClaimTools::authorizePayment);
        return t;
    }

    static String getClaimInfo(Map<String, Object> inputs) {
        return fixture(caseNo(inputs)).status;
    }

    static String assessLiability(Map<String, Object> inputs) {
        return fixture(caseNo(inputs)).documents;
    }

    static String scoreFraudRisk(Map<String, Object> inputs) {
        return fixture(caseNo(inputs)).fraud;
    }

    static String calcDeductible(Map<String, Object> inputs) {
        return fixture(caseNo(inputs)).payout;
    }

    static String authorizePayment(Map<String, Object> inputs) {
        return fixture(caseNo(inputs)).largeAmount;
    }

    private static String caseNo(Map<String, Object> inputs) {
        Object v = inputs == null ? null : inputs.get("caseNo");
        return v == null ? "" : String.valueOf(v);
    }

    record CaseFixture(String status, String documents, String fraud, String payout, String largeAmount) {
    }

    static CaseFixture fixture(String caseNo) {
        // Lenient default: blank/unknown caseNo → REDUCE. The PEV real-LLM e2e is a soft-observe
        // channel proof — the LLM planner may not surface the case number in every node description,
        // and the e2e must not false-DeviceFailure on a missing arg (that pollutes the honest
        // DeviceFailure semantics reserved for the always-throw tool in DeviceFailureDegradesE2eTest).
        // Defaulting to the standard fixture keeps the data channel observable. When the planner
        // does include the case number, ToolBackedExecutor's parseArgs extracts it and fidelity is
        // preserved; this default only covers the omission case.
        if (caseNo == null || caseNo.isBlank()) {
            return REDUCE;
        }
        return switch (caseNo) {
            case CLM_REDUCE -> REDUCE;
            case CLM_ADVERSARY -> ADVERSARY;
            default -> REDUCE;
        };
    }

}
