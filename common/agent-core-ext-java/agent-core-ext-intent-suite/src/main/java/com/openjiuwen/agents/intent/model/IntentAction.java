/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Action produced by a selected intent result function.
 *
 * @since 0.1.0
 */
public sealed interface IntentAction permits ReturnAction, InvokeToolAction, FinishAction {
}
