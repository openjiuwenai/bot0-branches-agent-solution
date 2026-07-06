/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.types;

/**
 * A criterion violation — returned by {@link com.openjiuwen.agents.reactrails.verification.CriteriaVerifier}
 * when a success criterion is not met.
 *
 * <p>Minimal local twin (agent-core-java jar has no {@code Violation} type in public API).
 */
public record Violation(String criterion, String reason) {}