/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import com.openjiuwen.agents.intent.api.IntentResultFunction;

/**
 * User-defined intent catalog entry.
 *
 * @param id unique intent identifier
 * @param description semantic matching description
 * @param resultFunction function that produces the selected intent action
 */
public record CustomIntentRegistration(String id, String description, IntentResultFunction resultFunction) {
}
