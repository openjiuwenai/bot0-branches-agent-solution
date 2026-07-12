/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import org.a2aproject.sdk.spec.AgentCard;

/** Determines whether card text may enter an Agent LLM context. */
@FunctionalInterface
public interface A2AContentTrustEvaluator {
    boolean isTrusted(AgentCard card);
}
