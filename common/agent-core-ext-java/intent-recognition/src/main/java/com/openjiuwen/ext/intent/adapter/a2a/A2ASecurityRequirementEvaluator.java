/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.SecurityRequirement;

/** Determines whether caller-owned credentials can satisfy effective A2A security requirements. */
@FunctionalInterface
public interface A2ASecurityRequirementEvaluator {
    boolean canSatisfy(AgentCard card, List<SecurityRequirement> requirements);
}
