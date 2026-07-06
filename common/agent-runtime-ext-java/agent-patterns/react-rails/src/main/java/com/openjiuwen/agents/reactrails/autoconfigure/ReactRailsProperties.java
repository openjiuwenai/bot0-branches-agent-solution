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
 */
@ConfigurationProperties(prefix = "reactrails")
public class ReactRailsProperties {

    /** Comma-separated success criteria for CriteriaVerificationRail. Empty = skip criteria rail. */
    private List<String> criteria = new ArrayList<>();

    /** Maximum replan count for ReplanRail. -1 = disable replan rail. */
    private int maxReplan = 2;

    /**
     * @return the success criteria list
     */
    public List<String> getCriteria() {
        return criteria;
    }

    /**
     * @param criteria the success criteria to set
     */
    public void setCriteria(List<String> criteria) {
        this.criteria = criteria;
    }

    /**
     * @return the max replan count
     */
    public int getMaxReplan() {
        return maxReplan;
    }

    /**
     * @param maxReplan the max replan count to set
     */
    public void setMaxReplan(int maxReplan) {
        this.maxReplan = maxReplan;
    }
}