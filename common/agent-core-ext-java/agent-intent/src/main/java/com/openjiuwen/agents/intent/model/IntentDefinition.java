/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import com.openjiuwen.agents.intent.api.IntentResultFunction;

/**
 * Initialized intent and its bound result behavior.
 *
 * @param id unique intent identifier
 * @param description semantic matching description
 * @param resultFunction bound result function
 * @param resultArguments immutable function arguments
 *
 * @since 0.1.0
 */
public record IntentDefinition(String id, String description, IntentResultFunction resultFunction,
        IntentResultArguments resultArguments) {
}
