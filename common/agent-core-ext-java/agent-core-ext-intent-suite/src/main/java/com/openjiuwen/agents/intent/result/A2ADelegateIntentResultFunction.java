/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.result;

import com.openjiuwen.agents.intent.api.IntentExecutionContext;
import com.openjiuwen.agents.intent.model.A2ADelegateArguments;
import com.openjiuwen.agents.intent.model.IntentAction;
import com.openjiuwen.agents.intent.model.IntentResultArguments;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.spi.IntentResultFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Shared result function that routes an Agent Card intent to the runtime delegate target.
 *
 * @since 0.1.0
 */
public final class A2ADelegateIntentResultFunction implements IntentResultFunction {
    /**
     * Runtime-internal target consumed by the runtime extension.
     */
    public static final String TOOL_NAME = "a2a_delegate";

    private static final Logger log = LoggerFactory.getLogger(A2ADelegateIntentResultFunction.class);

    @Override
    public IntentAction apply(IntentExecutionContext context) {
        IntentResultArguments resultArguments = context.resultArguments();
        if (!(resultArguments instanceof A2ADelegateArguments delegateArguments)) {
            throw new IllegalArgumentException("selected intent does not contain A2A delegate arguments");
        }
        log.info("Intent result routes to A2A delegate remoteAgentId={} semanticLength={}",
                delegateArguments.remoteAgentId(), context.routingSemantic().length());
        return new InvokeToolAction(TOOL_NAME,
                Map.of("agentName", delegateArguments.remoteAgentId(), "remoteInput", context.routingSemantic()));
    }
}
