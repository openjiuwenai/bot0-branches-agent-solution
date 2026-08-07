/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.result;

import com.openjiuwen.agents.intent.api.IntentExecutionContext;
import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.model.A2ADelegateArguments;
import com.openjiuwen.agents.intent.model.IntentAction;
import com.openjiuwen.agents.intent.model.IntentResultArguments;
import com.openjiuwen.agents.intent.model.InvokeToolAction;

import java.util.Map;

/**
 * Shared result function that routes an Agent Card intent to the runtime delegate target.
 */
public final class A2ADelegateIntentResultFunction implements IntentResultFunction {
    /**
     * Runtime-internal target consumed by the runtime extension.
     */
    public static final String TOOL_NAME = "a2a_delegate";

    @Override
    public IntentAction apply(IntentExecutionContext context) {
        IntentResultArguments resultArguments = context.resultArguments();
        if (!(resultArguments instanceof A2ADelegateArguments delegateArguments)) {
            throw new IllegalArgumentException("selected intent does not contain A2A delegate arguments");
        }
        return new InvokeToolAction(TOOL_NAME,
                Map.of("agentName", delegateArguments.remoteAgentId(), "remoteInput", context.routingSemantic()));
    }
}
