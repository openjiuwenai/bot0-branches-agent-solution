/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import com.openjiuwen.agents.intent.model.IntentDefinition;

import java.util.Optional;

/**
 * Selects one matchable intent for a single execution context.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface IntentMatcher {
    /**
     * Matches the routing semantic against the current catalog snapshot.
     *
     * @param context single-call execution context
     * @return selected catalog intent, or empty when no intent matches
     * @throws IntentMatchException when matching fails
     */
    Optional<IntentDefinition> match(IntentExecutionContext context) throws IntentMatchException;
}
