/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.deepagent.IntentDeepAgentBinder;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Installs Core intent routing and Runtime A2A delegation on DeepAgent instances. */
public final class IntentDeepAgentInstaller {
    private static final Logger log = LoggerFactory.getLogger(IntentDeepAgentInstaller.class);

    private final IntentDeepAgentBinder binder;
    private final IntentSuite suite;
    private final A2ARemoteAgentCardRegistry registry;
    private final boolean exposeAgentCardTools;
    private final Map<DeepAgent, Boolean> installed = Collections.synchronizedMap(new WeakHashMap<>());

    public IntentDeepAgentInstaller(IntentDeepAgentBinder binder, IntentSuite suite,
            A2ARemoteAgentCardRegistry registry, boolean exposeAgentCardTools) {
        this.binder = Objects.requireNonNull(binder, "binder");
        this.suite = Objects.requireNonNull(suite, "suite");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.exposeAgentCardTools = exposeAgentCardTools;
    }

    public void install(Object agent) {
        if (!(agent instanceof DeepAgent deepAgent)) {
            log.info("Intent routing supports DeepAgent only, skip agentType={}",
                    agent == null ? "null" : agent.getClass().getName());
            return;
        }
        synchronized (installed) {
            if (installed.containsKey(deepAgent)) {
                return;
            }
            deepAgent.getAgent().registerRail(new A2ADelegateRail(registry));
            binder.bind(deepAgent, suite);
            installed.put(deepAgent, Boolean.TRUE);
        }
    }

    public boolean exposeAgentCardTools() {
        return exposeAgentCardTools;
    }
}
