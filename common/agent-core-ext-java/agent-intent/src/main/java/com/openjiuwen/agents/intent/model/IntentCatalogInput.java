/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.List;

/**
 * Complete dynamic intent catalog input.
 *
 * @param agentCards all current remote Agent Cards
 * @param customIntents all current custom intents
 * @param fallback optional fallback intent
 */
public record IntentCatalogInput(List<AgentCardInput> agentCards, List<CustomIntentRegistration> customIntents,
        CustomIntentRegistration fallback) {
    /**
     * Creates an immutable complete catalog input.
     */
    public IntentCatalogInput {
        agentCards = agentCards == null ? List.of() : List.copyOf(agentCards);
        customIntents = customIntents == null ? List.of() : List.copyOf(customIntents);
    }

    /**
     * Returns an empty catalog input.
     *
     * @return empty catalog
     */
    public static IntentCatalogInput empty() {
        return new IntentCatalogInput(List.of(), List.of(), null);
    }
}
