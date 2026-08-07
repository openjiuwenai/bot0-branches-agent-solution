/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Synchronous result returned directly to the model.
 *
 * @param result model-visible result
 */
public record ReturnAction(Object result) implements IntentAction {
}
