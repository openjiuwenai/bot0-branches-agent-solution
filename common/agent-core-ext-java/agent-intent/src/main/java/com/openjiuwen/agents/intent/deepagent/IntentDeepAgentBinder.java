/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.prompt.DefaultIntentPrompt;
import com.openjiuwen.harness.deep_agent.DeepAgent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Installs the intent Tool and request prompt on a DeepAgent.
 */
public final class IntentDeepAgentBinder {
    private final Map<DeepAgent, IntentSuite> bindings = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Binds one global suite to a DeepAgent. Repeating the same binding is a no-op.
     *
     * @param deepAgent target DeepAgent
     * @param suite global intent suite
     */
    public void bind(DeepAgent deepAgent, IntentSuite suite) {
        Objects.requireNonNull(deepAgent, "deepAgent");
        Objects.requireNonNull(suite, "suite");
        synchronized (bindings) {
            IntentSuite existing = bindings.get(deepAgent);
            if (existing == suite) {
                return;
            }
            if (existing != null) {
                throw new IllegalStateException("DeepAgent is already bound to another IntentSuite");
            }
            if (deepAgent.getAgent().getAbilityManager().get(IntentRoutingRail.TOOL_NAME) != null) {
                throw new IllegalStateException(
                        "DeepAgent already contains a Tool named " + IntentRoutingRail.TOOL_NAME);
            }
            IntentRoutingRail routingRail = new IntentRoutingRail(suite);
            IntentPromptRail promptRail = new IntentPromptRail(routingRail.toolCard(),
                    DefaultIntentPrompt.routingInstructions(suite.config().prompt()));
            deepAgent.getAgent().registerRail(routingRail);
            deepAgent.getAgent().registerRail(promptRail);
            bindings.put(deepAgent, suite);
        }
    }
}
