/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Result state assigned by the suite execution flow.
 *
 * @since 0.1.0
 */
public enum IntentDecisionStatus {
    MATCHED,
    FALLBACK,
    UNMATCHED,
    FAILED
}
