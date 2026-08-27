/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.List;

/**
 * Complete initialized intent directory.
 *
 * @param matchableIntents intents considered by a matcher
 * @param fallback optional fallback intent, never considered by a matcher
 *
 * @since 0.1.0
 */
public record InitializedIntents(List<IntentDefinition> matchableIntents, IntentDefinition fallback) {
    /**
     * Creates an immutable initialized directory.
     */
    public InitializedIntents {
        matchableIntents = matchableIntents == null ? List.of() : List.copyOf(matchableIntents);
    }
}
