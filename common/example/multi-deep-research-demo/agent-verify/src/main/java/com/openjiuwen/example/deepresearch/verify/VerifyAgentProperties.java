/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import java.util.List;

/**
 * Pure POJO holding configuration for the verify ReAct sub-agent.
 *
 * <p>Library tier: depends only on JDK + agent-core-java + react-rails, no Spring annotations.
 * The runtime wrapper subclasses this to attach {@code @ConfigurationProperties}.
 *
 * @since 2026-07-14
 */
public class VerifyAgentProperties {
    private String agentId = "verify-agent";
    private String agentName = "VerifyAgent";
    private String agentDescription = "LLM judge that validates a draft research report against 对比矩阵/引用来源/置信度 anchors";

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean isSslVerify = true;
    private Double temperature = 0.0;
    private Double topP = 0.8;

    private int maxIterations = 3;
    private String sysOperationId = "deep-research-verify-agent";

    /**
     * The react-rails CriteriaReplanBridgeRail success criteria. Each criterion is a
     * <b>happy-path phrase</b> — a phrase the LLM emits <em>only when the report
     * genuinely covers that aspect</em>. This is the operating convention of
     * {@code RuleBasedCriteriaVerifier}: {@code output.contains(criterion)} works
     * as a coarse correctness proxy only when the phrase appears in success
     * outputs and does <em>not</em> appear in failure outputs.
     *
     * <p>Bad choices we tried first and why:
     * <ul>
     *   <li>Topic anchor {@code "对比矩阵"} → also appears in "对比矩阵: 缺失" →
     *       rail 100% false-PASS.</li>
     *   <li>Directive {@code "必须包含对比矩阵"} → LLM never verbatim quotes
     *       requirements → rail 100% false-FAIL.</li>
     * </ul>
     *
     * <p>Current choice: the LLM writes {@code <anchor>已覆盖} only when it
     * affirmatively confirms coverage, and {@code <anchor>缺失} otherwise. The
     * criterion is the whole covered-phrase, so a "缺失" output does NOT match.
     */
    private List<String> criteria = List.of("对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖");

    /**
     * Max steering retries when a criterion fails before forceFinish(degraded). The
     * bridge rail uses a shared {@code ReplanRail} counter. 1 = single steer attempt.
     */
    private int maxReplan = 1;

    private String systemPrompt = """
            You are the verification sub-agent of a multi-agent deep-research system.
            You have NO tools. You receive one input: a draft research report.

            Task:
            Judge whether the report covers three aspects: 对比矩阵, 引用来源, 置信度.

            Output format (strict — the outer verification rail keyword-matches on
            these exact phrases):
            - Line 1: `判定通过` if all three aspects are covered, otherwise `判定不通过`.
            - For each aspect that IS covered, add ONE line, EXACTLY:
                `对比矩阵已覆盖`
                `引用来源已覆盖`
                `置信度已覆盖`
              Only emit these phrases when the aspect is genuinely present.
            - For each aspect that is NOT covered, add ONE line, EXACTLY:
                `对比矩阵缺失`
                `引用来源缺失`
                `置信度缺失`

            Rules:
            - Do NOT rewrite the report. Do NOT fabricate content.
            - Do NOT call any tool — you have none.
            - Never write "<anchor>已覆盖" unless the aspect is truly present.
            - Do not paraphrase the coverage phrases; write them verbatim.
            """;

    /**
     * Asserts that the mandatory LLM settings ({@code api-key} / {@code api-base} /
     * {@code model-name}) have been supplied. Called by the runtime wrapper during
     * bean initialisation so misconfiguration surfaces before the first request.
     *
     * @throws IllegalStateException if any of the required properties is blank
     */
    public void requireConfigured() {
        requireText(apiKey, "verify-agent.llm.api-key");
        requireText(apiBase, "verify-agent.llm.api-base");
        requireText(modelName, "verify-agent.llm.model-name");
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentDescription() {
        return agentDescription;
    }

    public void setAgentDescription(String agentDescription) {
        this.agentDescription = agentDescription;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public boolean isSslVerify() {
        return isSslVerify;
    }

    public void setSslVerify(boolean isSslVerify) {
        this.isSslVerify = isSslVerify;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getSysOperationId() {
        return sysOperationId;
    }

    public void setSysOperationId(String sysOperationId) {
        this.sysOperationId = sysOperationId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<String> getCriteria() {
        return criteria;
    }

    public void setCriteria(List<String> criteria) {
        this.criteria = criteria;
    }

    public int getMaxReplan() {
        return maxReplan;
    }

    public void setMaxReplan(int maxReplan) {
        this.maxReplan = maxReplan;
    }
}
