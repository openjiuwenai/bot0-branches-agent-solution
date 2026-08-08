/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.spi;

import com.openjiuwen.agents.intent.api.IntentExecutionContext;
import com.openjiuwen.agents.intent.exception.IntentResultException;
import com.openjiuwen.agents.intent.model.IntentAction;

/**
 * SPI for producing the action bound to one initialized intent.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface IntentResultFunction {
    /**
     * Produces the selected intent action synchronously.
     *
     * @param context single-call execution context with selected intent
     * @return intent action
     * @throws IntentResultException when action generation fails
     */
    IntentAction apply(IntentExecutionContext context) throws IntentResultException;
}
