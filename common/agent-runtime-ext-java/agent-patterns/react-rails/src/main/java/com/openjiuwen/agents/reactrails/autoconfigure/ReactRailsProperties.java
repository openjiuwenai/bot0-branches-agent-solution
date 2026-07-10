/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for react-rails cognitive rails.
 *
 * <p>Bind via {@code reactrails.*} prefix in application.properties/yml:
 * <pre>
 * reactrails.criteria=给出配置建议,引用风险评估
 * reactrails.max-replan=2
 * </pre>
 *
 * @since 2026-07
 */
@ConfigurationProperties(prefix = "reactrails")
public class ReactRailsProperties {
    /**
     * Comma-separated success criteria for CriteriaVerificationRail. Empty = skip criteria rail.
     */
    private List<String> criteria = new ArrayList<>();

    /**
     * Maximum replan count for ReplanRail. -1 = disable replan rail.
     */
    private int maxReplan = 2;

    /**
     * Enable message history compression on __replan__ tool call (default off).
     */
    private boolean isHistoryCompression = false;

    /**
     * Enable one-shot injection of the "先扩后收" (widen then converge)
     * first-principles strategy prompt into the LLM system message on the
     * first real model invocation.
     *
     * <p>Only effective when the agent's LLM is a
     * {@link com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel}
     * instance. The mode is set globally via the static channel.
     */
    private boolean isFirstPrinciplesInject = false;

    /**
     * Enable the PreCompletionChecklistRail (PLAN/BUILD phase guardrail).
     * Default = true. When enabled, the rail injects phase-aware system prompts
     * via {@link com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel}.
     */
    private boolean isChecklistEnabled = true;

    /**
     * Max iterations in PLAN phase before switching to BUILD.
     * Must be >= 1. Default = 2.
     */
    private int checklistMaxPlanRounds = 2;

    /**
     * Gets configured success criteria.
     *
     * @return the success criteria list
     */
    public List<String> getCriteria() {
        return criteria;
    }

    /**
     * Sets configured success criteria.
     *
     * @param criteria the success criteria to set
     */
    public void setCriteria(List<String> criteria) {
        this.criteria = criteria;
    }

    /**
     * Gets the maximum allowed replan count.
     *
     * @return the max replan count
     */
    public int getMaxReplan() {
        return maxReplan;
    }

    /**
     * Sets the maximum allowed replan count.
     *
     * @param maxReplan the max replan count to set
     */
    public void setMaxReplan(int maxReplan) {
        this.maxReplan = maxReplan;
    }

    /**
     * Checks whether history compression is enabled.
     *
     * @return true if history compression on __replan__ is enabled
     */
    public boolean isHistoryCompression() {
        return isHistoryCompression;
    }

    /**
     * Enables or disables history compression.
     *
     * @param isHistoryCompression enable or disable history compression
     */
    public void setHistoryCompression(boolean isHistoryCompression) {
        this.isHistoryCompression = isHistoryCompression;
    }

    /**
     * Checks whether first-principles prompt injection is enabled.
     *
     * @return true if first-principles inject is enabled
     */
    public boolean isFirstPrinciplesInject() {
        return isFirstPrinciplesInject;
    }

    /**
     * Enables or disables first-principles prompt injection.
     *
     * @param isFirstPrinciplesInject enable or disable first-principles injection
     */
    public void setFirstPrinciplesInject(boolean isFirstPrinciplesInject) {
        this.isFirstPrinciplesInject = isFirstPrinciplesInject;
    }

    /**
     * Checks whether the pre-completion checklist rail is enabled.
     *
     * @return true if PreCompletionChecklistRail is enabled
     */
    public boolean isChecklistEnabled() {
        return isChecklistEnabled;
    }

    /**
     * Enables or disables the pre-completion checklist rail.
     *
     * @param isChecklistEnabled enable or disable PreCompletionChecklistRail
     */
    public void setChecklistEnabled(boolean isChecklistEnabled) {
        this.isChecklistEnabled = isChecklistEnabled;
    }

    /**
     * Gets the maximum PLAN phase rounds.
     *
     * @return max PLAN phase rounds before switching to BUILD
     */
    public int getChecklistMaxPlanRounds() {
        return checklistMaxPlanRounds;
    }

    /**
     * Sets the maximum PLAN phase rounds.
     *
     * @param v max PLAN phase rounds (must be >= 1)
     * @throws IllegalArgumentException if v < 1
     */
    public void setChecklistMaxPlanRounds(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("checklistMaxPlanRounds must be >= 1, got: " + v);
        }
        this.checklistMaxPlanRounds = v;
    }
}
