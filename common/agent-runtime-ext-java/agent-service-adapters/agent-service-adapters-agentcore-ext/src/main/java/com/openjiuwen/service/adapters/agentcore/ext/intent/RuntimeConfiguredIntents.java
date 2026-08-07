/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import com.openjiuwen.agents.intent.model.CustomIntentRegistration;

import java.util.List;

/**
 * Startup custom intents after Spring Bean names have been resolved.
 *
 * @param customIntents
 *            complete custom intent list
 * @param fallback
 *            optional fallback
 *
 * @since 0.1.0
 */
public record RuntimeConfiguredIntents(List<CustomIntentRegistration> customIntents,
        CustomIntentRegistration fallback) {
    public RuntimeConfiguredIntents {
        customIntents = customIntents == null ? List.of() : List.copyOf(customIntents);
    }
}
