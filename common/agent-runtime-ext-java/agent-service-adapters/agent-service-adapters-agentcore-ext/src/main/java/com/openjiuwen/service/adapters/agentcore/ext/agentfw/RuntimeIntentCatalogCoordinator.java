/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.exception.IntentInitializationException;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.RuntimeConfiguredIntents;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogSnapshot;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aggregates Runtime intent sources and atomically replaces the Core catalog.
 *
 * @since 0.1.0
 */
public final class RuntimeIntentCatalogCoordinator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(RuntimeIntentCatalogCoordinator.class);

    private final IntentSuite suite;
    private final A2ARemoteAgentCardRegistry registry;
    private final ReentrantLock updateLock = new ReentrantLock();

    private List<RemoteAgentEntry> remoteEntries = List.of();
    private List<CustomIntentRegistration> customIntents;
    private CustomIntentRegistration fallback;
    private long remoteCatalogVersion = -1L;

    /**
     * Creates a coordinator for all Runtime-owned intent sources.
     *
     * @param suite
     *            global intent suite
     * @param registry
     *            remote Agent Card registry
     * @param configuredIntents
     *            static custom and fallback intents
     */
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
            replaceRemoteSnapshot(snapshot);
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * Rebuilds the complete intent catalog from a newer remote Agent Card snapshot.
     *
     * @param event
     *            remote catalog update event
     */
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
            FutureTask<Void> refresh = new FutureTask<>(() -> {
                replaceRemoteSnapshot(event.snapshot());
                return null;
            });
            refresh.run();
            try {
                refresh.get();
            } catch (InterruptedException exception) {
                log.error("Interrupted while refreshing intent catalog from remote Agent Card version={}",
                        event.snapshot().version(), exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
                if (cause instanceof Error error) {
                    throw error;
                }
                log.error("Failed to refresh intent catalog from remote Agent Card version={}",
                        event.snapshot().version(), cause);
            }
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * Replaces all dynamic custom intents and the fallback registration.
     *
     * @param customIntents
     *            complete custom intent list
     * @param fallback
     *            fallback registration, or null to remove it
     */
    public void replaceCustomIntents(List<CustomIntentRegistration> customIntents, CustomIntentRegistration fallback) {
        List<CustomIntentRegistration> replacement = customIntents == null ? List.of() : List.copyOf(customIntents);
        updateLock.lock();
        try {
            refresh(remoteEntries, replacement, fallback, remoteCatalogVersion);
            this.customIntents = replacement;
            this.fallback = fallback;
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * Returns the latest accepted remote catalog version.
     *
     * @return remote catalog version
     */
    public long remoteCatalogVersion() {
        updateLock.lock();
        try {
            return remoteCatalogVersion;
        } finally {
            updateLock.unlock();
        }
    }

    private void replaceRemoteSnapshot(RemoteAgentCatalogSnapshot snapshot) {
        List<RemoteAgentEntry> replacement = snapshot.entries() == null ? List.of() : List.copyOf(snapshot.entries());
        refresh(replacement, customIntents, fallback, snapshot.version());
        remoteEntries = replacement;
        remoteCatalogVersion = snapshot.version();
    }

    private void refresh(List<RemoteAgentEntry> remoteEntries, List<CustomIntentRegistration> customIntents,
            CustomIntentRegistration fallback, long remoteCatalogVersion) {
        InitializedIntents currentIntents = suite.snapshot().initializedIntents();
        if (remoteEntries.isEmpty() && customIntents.isEmpty() && fallback == null
                && currentIntents.matchableIntents().isEmpty() && currentIntents.fallback() == null) {
            return;
        }
        List<AgentCardInput> cards = remoteEntries.stream().map(RuntimeIntentCatalogCoordinator::toAgentCardInput)
                .toList();
        long suiteVersion = suite.replaceCatalog(new IntentCatalogInput(cards, customIntents, fallback));
        log.info(
                "Replaced complete intent catalog suiteVersion={} remoteCatalogVersion={} remoteAgents={} "
                        + "customIntents={} fallback={}",
                suiteVersion, remoteCatalogVersion, cards.size(), customIntents.size(), fallback != null);
    }

    private static AgentCardInput toAgentCardInput(RemoteAgentEntry entry) {
        if (entry == null || entry.card() == null) {
            throw new IntentInitializationException("remote Agent Card entry must not be null");
        }
        return new AgentCardInput(entry.card(), entry.name());
    }
}
