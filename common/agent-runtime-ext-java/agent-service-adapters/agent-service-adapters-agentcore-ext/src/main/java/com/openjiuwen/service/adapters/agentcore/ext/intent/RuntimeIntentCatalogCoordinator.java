/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCatalogSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Aggregates Runtime intent sources and atomically replaces the Core catalog. */
public final class RuntimeIntentCatalogCoordinator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(RuntimeIntentCatalogCoordinator.class);

    private final IntentSuite suite;
    private final A2ARemoteAgentCardRegistry registry;
    private final ReentrantLock updateLock = new ReentrantLock();

    private List<A2ARemoteAgentCardRegistry.RemoteAgentEntry> remoteEntries = List.of();
    private List<CustomIntentRegistration> customIntents;
    private CustomIntentRegistration fallback;
    private long remoteCatalogVersion = -1L;

    public RuntimeIntentCatalogCoordinator(IntentSuite suite, A2ARemoteAgentCardRegistry registry,
            RuntimeConfiguredIntents configuredIntents) {
        this.suite = Objects.requireNonNull(suite, "suite");
        this.registry = Objects.requireNonNull(registry, "registry");
        RuntimeConfiguredIntents configured = Objects.requireNonNull(configuredIntents, "configuredIntents");
        customIntents = configured.customIntents();
        fallback = configured.fallback();
    }

    @Override
    public void afterPropertiesSet() {
        RemoteAgentCatalogSnapshot snapshot = registry.snapshot();
        updateLock.lock();
        try {
            acceptRemoteSnapshot(snapshot);
            refresh();
        } finally {
            updateLock.unlock();
        }
    }

    @EventListener
    public void onRemoteAgentCatalogChanged(RemoteAgentCatalogChangedEvent event) {
        if (event == null || event.snapshot() == null) {
            return;
        }
        updateLock.lock();
        try {
            if (event.snapshot().version() <= remoteCatalogVersion) {
                return;
            }
            acceptRemoteSnapshot(event.snapshot());
            try {
                refresh();
            } catch (RuntimeException exception) {
                log.error("Failed to refresh intent catalog from remote Agent Card version={}", remoteCatalogVersion,
                        exception);
            }
        } finally {
            updateLock.unlock();
        }
    }

    public void replaceCustomIntents(List<CustomIntentRegistration> customIntents, CustomIntentRegistration fallback) {
        List<CustomIntentRegistration> replacement = customIntents == null ? List.of() : List.copyOf(customIntents);
        updateLock.lock();
        try {
            this.customIntents = replacement;
            this.fallback = fallback;
            refresh();
        } finally {
            updateLock.unlock();
        }
    }

    public long remoteCatalogVersion() {
        updateLock.lock();
        try {
            return remoteCatalogVersion;
        } finally {
            updateLock.unlock();
        }
    }

    private void acceptRemoteSnapshot(RemoteAgentCatalogSnapshot snapshot) {
        remoteEntries = snapshot.entries() == null ? List.of() : List.copyOf(snapshot.entries());
        remoteCatalogVersion = snapshot.version();
    }

    private void refresh() {
        InitializedIntents currentIntents = suite.snapshot().initializedIntents();
        if (remoteEntries.isEmpty() && customIntents.isEmpty() && fallback == null
                && currentIntents.matchableIntents().isEmpty() && currentIntents.fallback() == null) {
            return;
        }
        List<AgentCardInput> cards = remoteEntries.stream().map(entry -> new AgentCardInput(entry.card(), entry.name()))
                .toList();
        long suiteVersion = suite.replaceCatalog(new IntentCatalogInput(cards, customIntents, fallback));
        log.info(
                "Replaced complete intent catalog suiteVersion={} remoteCatalogVersion={} remoteAgents={} "
                        + "customIntents={} fallback={}",
                suiteVersion, remoteCatalogVersion, cards.size(), customIntents.size(), fallback != null);
    }
}
