/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Final result of one suite resolution.
 *
 * @param status suite-assigned status
 * @param intentId selected intent identifier
 * @param action generated action
 * @param message failure or unmatched description
 *
 * @since 0.1.0
 */
public record IntentDecision(IntentDecisionStatus status, String intentId, IntentAction action, String message) {
}
