/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class RemoteA2aToolInstaller {

    private static final Logger log = LoggerFactory.getLogger(RemoteA2aToolInstaller.class);

    private final RemoteA2aAgentCardCache cache;
    private final Map<Object, Set<String>> installedToolNames = Collections.synchronizedMap(new WeakHashMap<>());

    private RemoteA2aToolInstaller(RemoteA2aAgentCardCache cache) {
        this.cache = cache;
    }

    public static RemoteA2aToolInstaller create(RemoteA2aAgentCardCache cache) {
        return new RemoteA2aToolInstaller(cache);
    }

    public void install(Object agent) {
        if (cache == null) {
            return;
        }
        if (agent instanceof String) {
            log.info("agent-id mode cannot install remote A2A tools in v1");
            return;
        }
        cache.refreshPendingOnce();
        cache.scheduleRetryUntilAllReady();
        var specs = cache.availableToolSpecs();
        if (specs.isEmpty()) {
            return;
        }
        BaseAgent target = resolveBaseAgent(agent);
        if (target == null) {
            log.warn("Unsupported agent type for remote A2A tool install: {}",
                    agent == null ? "null" : agent.getClass().getName());
            return;
        }
        Set<String> knownToolNames = installedToolNames.computeIfAbsent(target, key -> new LinkedHashSet<>());
        List<RemoteA2aAgentCardCache.RemoteA2aToolSpec> newSpecs = specs.stream()
                .filter(spec -> !knownToolNames.contains(spec.toolName()))
                .toList();
        if (newSpecs.isEmpty()) {
            return;
        }
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(newSpecs);
        target.registerRail(rail);
        newSpecs.forEach(spec -> knownToolNames.add(spec.toolName()));
        log.info("Installed remote A2A interrupt rail rail={} targetAgent={} tools={} toolDetails={}",
                rail.getClass().getSimpleName(),
                target.getClass().getName(),
                newSpecs.stream().map(RemoteA2aAgentCardCache.RemoteA2aToolSpec::toolName).toList(),
                newSpecs);
    }

    private static BaseAgent resolveBaseAgent(Object agent) {
        if (agent instanceof BaseAgent baseAgent) {
            return baseAgent;
        }
        if (agent instanceof DeepAgent deepAgent) {
            return deepAgent.getAgent();
        }
        return null;
    }
}
