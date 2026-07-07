/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.types;

import java.util.Map;

/**
 * A criterion violation — returned by {@link com.openjiuwen.agents.reactrails.verification.CriteriaVerifier}
 * when a success criterion is not met.
 *
 * <p>The {@code metadata} field carries optional gradient information from
 * {@link com.openjiuwen.agents.reactrails.verification.GradientVerifier}, enabling
 * precision steering (e.g. listing covered vs missing dimensions) in the bridge rail's
 * correction hint. Backward-compatible: {@code new Violation(criterion, reason)} defaults
 * to an empty metadata map.
 */
public record Violation(String criterion, String reason, Map<String, Object> metadata) {

    /**
     * Backward-compatible constructor for verifiers that do not produce gradient metadata
     * ({@link com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier},
     * {@link com.openjiuwen.agents.reactrails.verification.AdaptiveCriteriaVerifier}).
     */
    public Violation(String criterion, String reason) {
        this(criterion, reason, Map.of());
    }
}